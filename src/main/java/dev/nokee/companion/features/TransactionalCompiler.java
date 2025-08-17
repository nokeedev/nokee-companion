package dev.nokee.companion.features;

import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.VersionAwareCompiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.OutputCleaningCompiler;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;

final class TransactionalCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private final Compiler<T> delegateCompiler;
	private final OutputFileDirResolver outputFileDirResolver;
	private final Deleter deleter;

	public TransactionalCompiler(Compiler<T> delegateCompiler, OutputFileDirResolver outputFileDirResolver, ObjectFactory objects) {
		this.delegateCompiler = delegateCompiler;
		this.outputFileDirResolver = outputFileDirResolver;
		this.deleter = objects.newInstance(FileSystemOperationsBackedDeleter.class);
	}

	@Override
	public WorkResult execute(T spec) {
		File temporaryDirectory = new File(spec.getTempDir(), "compile-transaction");
		File stashDirectory = new File(temporaryDirectory, "stash");
		File backupDirectory = new File(temporaryDirectory, "backup");

		List<StashedFile> stashedFiles = stashFiles(spec.getRemovedSourceFiles(), spec.getObjectFileDir(), stashDirectory);
		List<StashedFile> backupFiles = stashFiles(spec.getSourceFiles(), spec.getObjectFileDir(), backupDirectory);

		// TODO: We should coerce the per-source options to ensure logging happens (start and done at the end)
		BuildOperationLogger delegate = spec.getOperationLogger();
		spec.setOperationLogger(new RollbackAwareBuildOperationLogger(delegate, stashedFiles, backupFiles, temporaryDirectory));

		// capture CommandLineToolInvocationFailure with message "C++ compiler failed while compiling " suffix
		return delegateCompiler.execute(spec);
	}

	private final class RollbackAwareBuildOperationLogger implements BuildOperationLogger, BuildOperationLoggerRef {
		private final BuildOperationLogger delegate;
		private final List<StashedFile> stashedFiles;
		private final List<StashedFile> backupFiles;
		private final File temporaryDirectory;
		private boolean failed = false;

		private RollbackAwareBuildOperationLogger(BuildOperationLogger delegate, List<StashedFile> stashedFiles, List<StashedFile> backupFiles, File temporaryDirectory) {
			this.delegate = delegate;
			this.stashedFiles = stashedFiles;
			this.backupFiles = backupFiles;
			this.temporaryDirectory = temporaryDirectory;
		}

		@Override
		public void start() {
			// already happened...
		}

		@Override
		public void operationSuccess(String description, String output) {
			delegate.operationSuccess(description, output);
		}

		@Override
		public void operationFailed(String description, String output) {
			failed = true;
			delegate.operationFailed(description, output);
		}

		@Override
		public void done() {
			if (failed) {
				stashedFiles.forEach(StashedFile::unstash);
				backupFiles.forEach(StashedFile::unstash);
			} else {
				try {
					deleter.deleteRecursively(temporaryDirectory);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			delegate.done();
		}

		@Override
		public String getLogLocation() {
			return delegate.getLogLocation();
		}

		@Override
		public UseCount useCount() {
			return ((BuildOperationLoggerRef) delegate).useCount();
		}
	}

	List<StashedFile> stashFiles(List<File> filesToStash, File objectFileDir, File stashDirectory) {
		return filesToStash.stream().map(file -> {
			File origFile = outputFileDirResolver.outputFileDir(file, objectFileDir).getParentFile();
			File stashedFile = outputFileDirResolver.outputFileDir(file, stashDirectory).getParentFile();

			if (origFile.exists()) {
				try {
					Files.createDirectories(stashedFile.toPath().getParent());
					Files.move(origFile.toPath(), stashedFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}

				return new StashedFile() {
					@Override
					public void unstash() {
						try {
							deleter.deleteRecursively(origFile);
							Files.move(stashedFile.toPath(), origFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
				};
			} else {
				return new StashedFile() {
					@Override
					public void unstash() {
						try {
							deleter.deleteRecursively(origFile);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
				};
			}
		}).collect(Collectors.toList());
	}

	private static abstract class StashedFile {
		public abstract void unstash();
	}


	public static OutputFileDirResolver outputFileDir(Compiler<?> nativeCompiler) {
		try {
			Field VersionAwareCompiler_compiler = VersionAwareCompiler.class.getDeclaredField("compiler");
			VersionAwareCompiler_compiler.setAccessible(true);
			nativeCompiler = (Compiler<?>) VersionAwareCompiler_compiler.get(nativeCompiler);

			Method OutputCleaningCompiler_getObjectFile = OutputCleaningCompiler.class.getDeclaredMethod("getObjectFile", File.class, File.class);
			OutputCleaningCompiler_getObjectFile.setAccessible(true);

			final Compiler<?> self = nativeCompiler;
			return (sourceFile, objectFileDir) -> {
				try {
					return (File) OutputCleaningCompiler_getObjectFile.invoke(self, objectFileDir, sourceFile);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new RuntimeException(e);
				}
			};
		} catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	interface OutputFileDirResolver {
		File outputFileDir(File sourceFile, File objectFileDir);
	}

	private interface Deleter {
		boolean deleteRecursively(File target) throws IOException;
	}

	/*private*/ static class FileSystemOperationsBackedDeleter implements Deleter {
		private final FileSystemOperations fileOperations;

		@Inject
		public FileSystemOperationsBackedDeleter(FileSystemOperations fileOperations) {
			this.fileOperations = fileOperations;
		}

		@Override
		public boolean deleteRecursively(File target) throws IOException {
			WorkResult result = WorkResults.didWork(false);
			for (int failedAttempts = 1; failedAttempts < 10; ++failedAttempts) {
				try {
					result = result.or(fileOperations.delete(spec -> spec.delete(target)));
				} catch (org.gradle.api.UncheckedIOException ignored1) {
					System.gc(); // this may help

					try {
						Thread.sleep(10L);
					} catch (InterruptedException ignored2) {
						Thread.currentThread().interrupt();
					}
				}
			}
			return result.getDidWork();
		}
	}
}
