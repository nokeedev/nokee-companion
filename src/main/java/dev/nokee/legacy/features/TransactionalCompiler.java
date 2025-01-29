package dev.nokee.legacy.features;

import org.apache.commons.io.FileUtils;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.VersionAwareCompiler;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.OutputCleaningCompiler;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

final class TransactionalCompiler<T extends NativeCompileSpec> implements Compiler<T> {
	private final Compiler<T> delegateCompiler;
	private final OutputFileDirResolver outputFileDirResolver;

	public TransactionalCompiler(Compiler<T> delegateCompiler, OutputFileDirResolver outputFileDirResolver) {
		this.delegateCompiler = delegateCompiler;
		this.outputFileDirResolver = outputFileDirResolver;
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
		spec.setOperationLogger(new BuildOperationLogger() {
			private boolean failed = false;

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
						FileUtils.deleteDirectory(temporaryDirectory);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				delegate.done();
			}

			@Override
			public String getLogLocation() {
				return delegate.getLogLocation();
			}
		});

		// capture CommandLineToolInvocationFailure with message "C++ compiler failed while compiling " suffix
		return delegateCompiler.execute(spec);
	}

	List<StashedFile> stashFiles(List<File> filesToStash, File objectFileDir, File stashDirectory) {
		return filesToStash.stream().map(file -> {
			File origFile = outputFileDirResolver.outputFileDir(file, objectFileDir).getParentFile();
			File stashedFile = outputFileDirResolver.outputFileDir(file, stashDirectory).getParentFile();

			if (origFile.exists()) {
				try {
					FileUtils.copyDirectory(origFile, stashedFile, true);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				return new StashedFile() {
					@Override
					public void unstash() {
						try {
							FileUtils.deleteDirectory(origFile);
							FileUtils.copyDirectory(stashedFile, origFile, true);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				};
			} else {
				return new StashedFile() {
					@Override
					public void unstash() {
						try {
							FileUtils.deleteDirectory(origFile);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				};
			}
		}).collect(Collectors.toList());
	}

	private abstract class StashedFile {
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
}
