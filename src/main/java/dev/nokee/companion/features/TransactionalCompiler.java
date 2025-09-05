package dev.nokee.companion.features;

import org.gradle.api.Action;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class TransactionalCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private static final Logger LOGGER = Logging.getLogger(TransactionalCompiler.class);
	private final Compiler<T> delegateCompiler;
	private final OutputFileDirResolver outputFileDirResolver;
	private final FileSystemOperations fileOperations;

	public TransactionalCompiler(Compiler<T> delegateCompiler, OutputFileDirResolver outputFileDirResolver, ObjectFactory objects, FileSystemOperations fileOperations) {
		this.delegateCompiler = delegateCompiler;
		this.outputFileDirResolver = outputFileDirResolver;
		this.fileOperations = fileOperations;
	}

	@Override
	public WorkResult execute(T spec) {
		File temporaryDirectory = new File(spec.getTempDir(), "compile-transaction");

		// Ensure the compile-transaction is clean
		try {
			fileOperations.delete(it -> it.delete(temporaryDirectory));
		} catch (Throwable ex) {
			// ignores, the sync later will "clean" individual transaction
			LOGGER.warn("Could not clean compile transaction.", ex);
		}

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
			}
			try {
				fileOperations.delete(spec -> spec.delete(temporaryDirectory));
			} catch (Throwable ex) {
				// leave it, and try cleaning it next time
				LOGGER.warn("Could not clean compile transaction.", ex);
			}
			delegate.done();
		}

		@Override
		public String getLogLocation() {
			return delegate.getLogLocation();
		}

		@Override
		public String id() {
			return ((BuildOperationLoggerRef) delegate).id();
		}
	}

	List<StashedFile> stashFiles(List<File> filesToStash, File objectFileDir, File stashDirectory) {
		return filesToStash.stream().map(file -> {
			File origFile = outputFileDirResolver.outputFileDir(file, objectFileDir).getParentFile();
			File stashedFile = outputFileDirResolver.outputFileDir(file, stashDirectory).getParentFile();

			if (origFile.exists()) {
				preserveFileDate(it -> {
					fileOperations.sync(spec -> spec.from(origFile).into(stashedFile).eachFile(details -> it.put(details.getFile(), new File(stashedFile, details.getPath()))));
				});

				return new StashedFile() {
					@Override
					public void unstash() {
						preserveFileDate(it -> {
							fileOperations.sync(spec -> spec.from(stashedFile).into(origFile).eachFile(details -> it.put(details.getFile(), new File(origFile, details.getPath()))));
						});
					}
				};
			} else {
				return new StashedFile() {
					@Override
					public void unstash() {
						fileOperations.delete(spec -> spec.delete(origFile));
					}
				};
			}
		}).collect(Collectors.toList());
	}

	private static abstract class StashedFile {
		public abstract void unstash();
	}

	private void preserveFileDate(Action<? super Map<File, File>> action) {
		Map<File, File> srcDsts = new HashMap<>();
		action.execute(srcDsts);
		srcDsts.forEach((src, dst) -> {
			if (!dst.setLastModified(src.lastModified())) {
				LOGGER.debug("Could not preserve file date for '" + dst + "'.");
			}
		});
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
}
