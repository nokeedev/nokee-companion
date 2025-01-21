package dev.nokee.legacy;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.VersionAwareCompiler;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.OutputCleaningCompiler;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.util.GradleVersion;
import org.gradle.work.InputChanges;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.compileTaskName;

@CacheableTask
public abstract /*final*/ class CppCompileTask extends CppCompile {
	@Override
	protected void compile(InputChanges inputs) {
		BuildOperationLogger operationLogger = this.getOperationLoggerFactory().newOperationLogger(this.getName(), this.getTemporaryDir());
		NativeCompileSpec spec = createCompileSpec();
		spec.setTargetPlatform(getTargetPlatform().get());
		spec.setTempDir(getTemporaryDir());
		spec.setObjectFileDir(getObjectFileDir().get().getAsFile());
		spec.include(getIncludes());
		spec.systemInclude(getSystemIncludes());
		spec.source(getSource());
		spec.setMacros(getMacros());
		spec.args(getCompilerArgs().get());
		spec.setPositionIndependentCode(isPositionIndependentCode());
		spec.setDebuggable(isDebuggable());
		spec.setOptimized(isOptimized());
		spec.setIncrementalCompile(inputs.isIncremental());
		spec.setOperationLogger(operationLogger);
		this.configureSpec(spec);
		NativeToolChainInternal nativeToolChain = (NativeToolChainInternal)getToolChain().get();
		NativePlatformInternal nativePlatform = (NativePlatformInternal)getTargetPlatform().get();
		PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

		setDidWork(doCompile(spec, platformToolProvider).getDidWork());
	}

	// Copied from AbstractNativeSourceCompileTask#doCompile with support for per-source options
	private <T extends NativeCompileSpec> WorkResult doCompile(T spec, PlatformToolProvider platformToolProvider) {
		Class<T> specType = Cast.uncheckedCast(spec.getClass());
		Compiler<T> baseCompiler = platformToolProvider.newCompiler(specType);
		Compiler<T> perSourceCompiler = new PerSourceCompiler<>(baseCompiler, perSourceOptions::forFile, () -> (T) createCompileSpec());
		Compiler<T> transactionalCompiler = newTransactionalCompiler(perSourceCompiler, outputFileDir(baseCompiler));
		Compiler<T> incrementalCompiler = getIncrementalCompiler().createCompiler(transactionalCompiler);
		Compiler<T> loggingCompiler = BuildOperationLoggingCompilerDecorator.wrap(incrementalCompiler);
		return loggingCompiler.execute(spec);
	}

	// We have to reach to AbstractNativeSourceCompileTask#incrementalCompiler
	private IncrementalCompilerBuilder.IncrementalCompiler getIncrementalCompiler() {
		try {
			Field AbstractNativeCompileTask__incrementalCompiler = AbstractNativeCompileTask.class.getDeclaredField("incrementalCompiler");
			AbstractNativeCompileTask__incrementalCompiler.setAccessible(true);
			return (IncrementalCompilerBuilder.IncrementalCompiler) AbstractNativeCompileTask__incrementalCompiler.get(this);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private <T extends NativeCompileSpec> Compiler<T> newTransactionalCompiler(Compiler<T> delegateCompiler, OutputFileDirResolver outputFileDirResolver) {
		return new Compiler<T>() {
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
						delegate.start();
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
		};
	}

	private OutputFileDirResolver outputFileDir(Compiler<?> nativeCompiler) {
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

	@Inject
	public CppCompileTask(ObjectFactory objects) {
		this.perSourceOptions = new AllSourceOptions<>(CompileOptions.class, objects);
	}

	//region Per-source Options
	private final AllSourceOptions<CompileOptions> perSourceOptions;

	@Nested // Required to track changes to the per-source options
	protected List<Action<?>> getSourceActions() {
		return perSourceOptions.entries.stream().map(it -> it.configureAction).collect(Collectors.toList());
	}

	// TODO: We may need to disallow lambda action. We should validate.
	public CppCompileTask source(Object source, Action<? super CompileOptions> action) {
		getSource().from(source);
		perSourceOptions.put(source, action);
		return this;
	}

	public static class AllSourceOptions<T> {
		// TODO: Use ActionSet
		/*private*/ final List<Entry<T>> entries = new ArrayList<>();
		private final Class<T> optionType;
		private final ObjectFactory objects;
		private final Key DEFAULT = new Key(new int[0]);

		@Nullable
		public Key forFile(File file) {
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).contains(file)) {
					indices.add(i);
				}
			}

			if (indices.isEmpty()) {
				return DEFAULT; // no per-source options (bucket 1)
			} else {
				// cacheable (bucket 2)
				return new Key(indices.stream().mapToInt(Integer::intValue).toArray());
			}
		}

		public class Key {
			private final int[] indices;

			private Key(int[] indices) {
				this.indices = indices;
			}

			@Override
			public boolean equals(Object o) {
				if (o == null || getClass() != o.getClass()) return false;
				Key key = (Key) o;
				return Arrays.equals(indices, key.indices);
			}

			@Override
			public int hashCode() {
				return Arrays.hashCode(indices);
			}

			@Override
			public String toString() {
				return "Key{" + Arrays.toString(indices) + "}";
			}

			public T get() {
				T result = objects.newInstance(optionType);
				for (int index : indices) {
					entries.get(index).configureAction.execute(result);
				}
				return result;
			}
		}

		private AllSourceOptions(Class<T> optionType, ObjectFactory objects) {
			this.optionType = optionType;
			this.objects = objects;
		}

		public void put(Object source, Action<? super T> action) {
			entries.add(new Entry<>(objects.fileCollection().from(source), action));
		}

		// TODO: Just be an action
		public static final class Entry<T> {
			private FileCollection sources;
			private Set<File> realizedFiles;
			private final Action<? super T> configureAction;

			public Entry(FileCollection sources, Action<? super T> configureAction) {
				this.sources = sources;
				this.configureAction = configureAction;
			}

			public boolean contains(File file) {
				if (realizedFiles == null) {
					realizedFiles = sources.getFiles();
					sources = null;
				}
				return realizedFiles.contains(file);
			}
		}
	}

	public interface CompileOptions {
		ListProperty<String> getCompilerArgs();
	}
	//endregion

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		@Inject
		public Rule() {}

		@Override
		public void apply(Project project) {
			project.getPlugins().withType(CppBasePlugin.class, __ -> {
				project.getComponents().withType(CppComponent.class, component -> {
					component.getBinaries().whenElementKnown(CppBinary.class, binary -> {
						project.getTasks().replace(compileTaskName(binary), CppCompileTask.class);
					});
				});
			});
		}
	}
}
