package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.provider.ProviderUtils;
import dev.nokee.commons.gradle.tasks.options.OptionsAware;
import dev.nokee.commons.gradle.tasks.options.SourceOptions;
import dev.nokee.commons.gradle.tasks.options.SourceOptionsAware;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.*;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.internal.Cast;
import org.gradle.internal.io.StreamByteBuffer;
import org.gradle.internal.operations.*;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.VersionAwareCompiler;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.internal.*;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.process.ExecOperations;
import org.gradle.process.internal.ExecException;
import org.gradle.work.InputChanges;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static dev.nokee.commons.names.CppNames.compileTaskName;
import static dev.nokee.companion.features.ReflectionUtils.*;
import static dev.nokee.companion.features.TransactionalCompiler.outputFileDir;

@CacheableTask
/*private*/ abstract /*final*/ class CppCompileTask extends CppCompile implements OptionsAware, SourceOptionsAware<NativeCompileOptions> {
	public static abstract class DefaultTaskOptions implements dev.nokee.commons.gradle.tasks.options.Options, CppCompile.Options {
		private ListProperty<String> compilerArgs;
		private SourceOptionsLookup allOptions;

		@Inject
		public DefaultTaskOptions() {}

		@Override
		public Provider<NativeCompileOptions> forSource(File file) {
			return allOptions.get(file);
		}

		@Nested
		@Override
		public abstract DefaultPreprocessorOptions getPreprocessorOptions();

		@Internal
		@Override
		public ListProperty<String> getCompilerArgs() {
			return compilerArgs;
		}

		public static abstract /*final*/ class DefaultPreprocessorOptions implements PreprocessorOptions {
			private final ObjectFactory objects;

			@Inject
			public DefaultPreprocessorOptions(ObjectFactory objects) {
				this.objects = objects;
			}

			@Override
			public void defines(Provider<?> definedMacros) {
				getDefinedMacros().addAll(definedMacros.map(it -> {
					List<DefinedMacro> result = new ArrayList<>();
					if (it instanceof Map) {
						((Map) it).forEach((name, definition) -> {
							if (definition == null) {
								result.add(objects.newInstance(NameOnlyMacro.class, name));
							} else {
								result.add(objects.newInstance(MacroWithDefinition.class, name, definition));
							}
						});
					} else if (it instanceof Iterable) {
						((Iterable) it).forEach(entry -> {
							if (entry instanceof DefinedMacro) {
								result.add((DefinedMacro) entry);
							} else {
								throw new UnsupportedOperationException();
							}
						});
					} else {
						throw new IllegalArgumentException();
					}
					return result;
				}));
			}

			@Override
			public void defines(Map<? extends String, ?> definedMacros) {
				definedMacros.forEach((name, definition) -> {
					if (definition == null) {
						getDefinedMacros().add(objects.newInstance(NameOnlyMacro.class, name));
					} else {
						getDefinedMacros().add(objects.newInstance(MacroWithDefinition.class, name, definition));
					}
				});
			}

			@Override
			public void define(String name) {
				getDefinedMacros().add(objects.newInstance(NameOnlyMacro.class, name));
			}

			@Override
			public void define(String name, Object definition) {
				getDefinedMacros().add(objects.newInstance(MacroWithDefinition.class, name, Objects.requireNonNull(definition, "'definition' must not be null")));
			}

			public static abstract /*final*/ class NameOnlyMacro implements DefinedMacro {
				private final String name;

				@Inject
				public NameOnlyMacro(String name) {
					this.name = name;
				}

				@Override
				public String getName() {
					return name;
				}

				@Override
				public @Nullable String getDefinition() {
					return null;
				}
			}

			public static abstract /*final*/ class MacroWithDefinition implements DefinedMacro {
				private final String name;
				private final Object definition;

				@Inject
				public MacroWithDefinition(String name, Object definition) {
					this.name = name;
					this.definition = definition;
				}

				@Override
				public String getName() {
					return name;
				}

				@Override
				public @Nullable String getDefinition() {
					return unpack(definition);
				}

				private @Nullable String unpack(@Nullable Object object) {
					if (object == null) {
						return null;
					} else if (object instanceof Provider) {
						return unpack(((Provider<?>) object).getOrNull());
					} else {
						return object.toString();
					}
				}
			}
		}
	}

	@Nested
	@Override
	public abstract DefaultTaskOptions getOptions();

	@Override
	protected void compile(InputChanges inputs) {
		BuildOperationLogger operationLogger = this.getOperationLoggerFactory().newOperationLogger(this.getName(), this.getTemporaryDir());
		IsolatableBuildOperationLogger.newLogger(operationLogger, logger -> {
			NativeCompileSpec spec = createCompileSpec();
			spec.setTargetPlatform(getTargetPlatform().get());
			spec.setTempDir(getTemporaryDir());
			spec.setObjectFileDir(getObjectFileDir().get().getAsFile());
			spec.include(getIncludes());
			spec.systemInclude(getSystemIncludes());
			spec.source(getSource());
			spec.setMacros(getMacros());
			spec.args(getCompilerArgs().get());
			for (CommandLineArgumentProvider argProvider : getOptions().getCompilerArgumentProviders().get()) {
				argProvider.asArguments().forEach(spec.getArgs()::add);
			}
			spec.setPositionIndependentCode(isPositionIndependentCode());
			spec.setDebuggable(isDebuggable());
			spec.setOptimized(isOptimized());
			spec.setIncrementalCompile(inputs.isIncremental());
			spec.setOperationLogger(logger);

			this.configureSpec(spec);

			NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) getToolChain().get();
			NativePlatformInternal nativePlatform = (NativePlatformInternal) getTargetPlatform().get();
			PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

			setDidWork(doCompile(spec, platformToolProvider).getDidWork());
		});
	}

	// Copied from AbstractNativeSourceCompileTask#doCompile with support for per-source options
	private <T extends NativeCompileSpec> WorkResult doCompile(T spec, PlatformToolProvider platformToolProvider) {
		Class<T> specType = Cast.uncheckedCast(spec.getClass());
		Compiler<T> baseCompiler = platformToolProvider.newCompiler(specType);

		Compiler<T> perSourceCompiler = baseCompiler;

		SourceOptions<NativeCompileOptions> allOptions = getAllSourceOptions().getOrNull();
		if (allOptions != null) {
			PerSourceCompiler.SourceSpecProvider<T> sourceSpecProvider = new PerSourceCompiler.SourceSpecProvider<T>() {
				@Override
				public Iterable<T> forFiles(Collection<File> files) {
					List<T> result = new ArrayList<>();
					for (SourceOptions.Group<NativeCompileOptions> groupedOptions : allOptions.forFiles(getObjects().fileCollection().from(files).getAsFileTree()).groupedByOptions()) {
						NativeCompileSpec newSpec = createCompileSpec();

						//region Copy default spec to new spec
						newSpec.setTargetPlatform(spec.getTargetPlatform());
						newSpec.setTempDir(spec.getTempDir());
						newSpec.getArgs().addAll(spec.getArgs());
						newSpec.getSystemArgs().addAll(spec.getSystemArgs());
						newSpec.setOperationLogger(spec.getOperationLogger());

						newSpec.setObjectFileDir(spec.getObjectFileDir());
						newSpec.getIncludeRoots().addAll(spec.getIncludeRoots());
						newSpec.getSystemIncludeRoots().addAll(spec.getSystemIncludeRoots());
						newSpec.setSourceFiles(spec.getSourceFiles());
						newSpec.setRemovedSourceFiles(spec.getRemovedSourceFiles());
						newSpec.setMacros(spec.getMacros());
						newSpec.setPositionIndependentCode(spec.isPositionIndependentCode());
						newSpec.setDebuggable(spec.isDebuggable());
						newSpec.setOptimized(spec.isOptimized());
						newSpec.setIncrementalCompile(spec.isIncrementalCompile());
						newSpec.setPrefixHeaderFile(spec.getPrefixHeaderFile());
						newSpec.setPreCompiledHeaderObjectFile(spec.getPreCompiledHeaderObjectFile());
						newSpec.setPreCompiledHeader(spec.getPreCompiledHeader());
						newSpec.setSourceFilesForPch(spec.getSourceFilesForPch());
						//endregion

						newSpec.setSourceFiles(groupedOptions.getSourceFiles()); // set only the bucket source
						newSpec.setRemovedSourceFiles(Collections.emptyList()); // do not remove any files

						// Namespace the temporary directory (i.e. where the options.txt will be written)
						newSpec.setTempDir(new File(spec.getTempDir(), groupedOptions.getUniqueId()));

						// Configure the bucket spec from the per-source options
						newSpec.args(groupedOptions.getOptions().getCompilerArgs().get());
						for (CommandLineArgumentProvider argumentProvider : groupedOptions.getOptions().getCompilerArgumentProviders().get()) {
							argumentProvider.asArguments().forEach(newSpec.getArgs()::add);
						}

						result.add((T) newSpec);
					}
					try {
						((AutoCloseable) getSourceOptions()).close(); // release some memory
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
					return result;
				}
			};
			WorkQueue queue = executor.noIsolation();
			perSourceCompiler = new PerSourceCompiler<>(baseCompiler, sourceSpecProvider, queue);

			// region Patching BuildOperationExecutor
			try {
				Compiler<T> compiler = baseCompiler;
				while (!(compiler instanceof AbstractCompiler)) {
					if (compiler instanceof VersionAwareCompiler) {
						compiler = readFieldValue(getField(compiler.getClass(), "compiler"), compiler);
					} else if (compiler instanceof OutputCleaningCompiler) {
						compiler = readFieldValue(getField(compiler.getClass(), "compiler"), compiler);
					}
				}

				updateFieldValue(getField(AbstractCompiler.class, "buildOperationExecutor"), compiler, objects.newInstance(WorkerBackedBuildOperationExecutor.class, queue));
				getLogger().debug("Patching the build operation executor was successful, enjoy light speed compilation!");
			} catch (Throwable e) {
				// do not patch... serial execution == slower
				getLogger().info("Could not patch the build operation executor, per-source option buckets will execute serially (aka slower).", e);
			}
			//endregion
		}

		Compiler<T> transactionalCompiler = perSourceCompiler;
		if (getOptions().getIncrementalAfterFailure().getOrElse(false) && spec.isIncrementalCompile()) {
			transactionalCompiler = new TransactionalCompiler<>(perSourceCompiler, outputFileDir(baseCompiler), getObjects(), getFileOperations());
		}
		Compiler<T> incrementalCompiler = getIncrementalCompiler().createCompiler(transactionalCompiler);
		Compiler<T> loggingCompiler = BuildOperationLoggingCompilerDecorator.wrap(incrementalCompiler);
		return loggingCompiler.execute(spec);
	}

	@Inject
	protected abstract ObjectFactory getObjects();

	@Inject
	protected abstract FileSystemOperations getFileOperations();

	/*private*/ static abstract /*final*/ class WorkerBackedBuildOperationExecutor implements BuildOperationExecutor {
		private final WorkQueue queue;

		@Inject
		public WorkerBackedBuildOperationExecutor(WorkQueue queue) {
			this.queue = queue;
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> action) {
			action.execute(new BuildOperationQueue<O>() {
				@Override
				public void add(O o) {
					assert o instanceof CommandLineToolInvocation;

					CommandLineToolInvocation invocation = (CommandLineToolInvocation) o;
					queue.submit(CommandLineToolInvocationAction.class, spec -> {
						spec.getEnvironment().set(invocation.getEnvironment());
						spec.getPath().from(invocation.getPath());
						spec.getWorkDirectory().set(invocation.getWorkDirectory());
						spec.getArgs().addAll(invocation.getArgs());
						spec.setLogger(invocation.getLogger());
						spec.getDescription().set(invocation.description().build().getDisplayName());
						spec.getExecutable().set(executableOf(worker));
						spec.getName().set(nameOf(worker));
					});
				}

				@Override
				public void cancel() {
					throw new UnsupportedOperationException();
				}

				@Override
				public void waitForCompletion() throws MultipleBuildOperationFailures {
					throw new UnsupportedOperationException();
				}

				@Override
				public void setLogLocation(String s) {
					// ignore... only used for error reporting
				}
			});
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> buildOperationWorker, Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			throw new UnsupportedOperationException();
		}

		public BuildOperationRef getCurrentOperation() {
			throw new UnsupportedOperationException();
		}
	}

	private static File executableOf(BuildOperationWorker<?> worker) {
		return readFieldValue(getField(worker.getClass(), "executable"), worker);
	}

	private static String nameOf(BuildOperationWorker<?> worker) {
		return readFieldValue(getField(worker.getClass(), "name"), worker);
	}

	// Allow jumping the isolation gap between current thread and worker thread (no-isolated)
	//   This is a workaround ONLY, we should use a build service that act as, in spirit, as the build operation logger.
	//   It would be used to track individual cli tool invocation and report success/failure in the log file.
	private static final class IsolatableBuildOperationLogger implements BuildOperationLogger, BuildOperationLoggerRef {
		private static final Map<String, BuildOperationLogger> LOGGERS = new ConcurrentHashMap<>();
		private final BuildOperationLogger delegate;

		private IsolatableBuildOperationLogger(BuildOperationLogger delegate) {
			this.delegate = delegate;
		}

		public static void newLogger(BuildOperationLogger delegate, Consumer<? super BuildOperationLogger> action) {
			BuildOperationLogger newLogger = new IsolatableBuildOperationLogger(delegate);
			LOGGERS.put(idOf(newLogger), newLogger);
			try {
				action.accept(newLogger);
			} finally {
				LOGGERS.remove(idOf(newLogger));
			}
		}

		public static BuildOperationLogger loggerOf(String id) {
			return LOGGERS.get(id);
		}

		public static String idOf(BuildOperationLogger logger) {
			return ((BuildOperationLoggerRef) logger).id();
		}

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
			delegate.operationFailed(description, output);
		}

		@Override
		public void done() {
			delegate.done();
		}

		@Override
		public String getLogLocation() {
			return delegate.getLogLocation();
		}

		@Override
		public String id() {
			return toString();
		}
	}

	/*private*/ static abstract /*final*/ class CommandLineToolInvocationAction implements WorkAction<CommandLineToolInvocationAction.Parameters> {
		public static abstract class Parameters implements WorkParameters {
			public abstract ListProperty<String> getArgs();
			public abstract ConfigurableFileCollection getPath();
			public abstract MapProperty<String, String> getEnvironment();
			public abstract DirectoryProperty getWorkDirectory();
			public abstract Property<String> getDescription();
			public abstract RegularFileProperty getExecutable();
			public abstract Property<String> getName();

			protected abstract Property<String> getLoggerId();

			public void setLogger(BuildOperationLogger logger) {
				IsolatableBuildOperationLogger.LOGGERS.put(IsolatableBuildOperationLogger.idOf(logger), logger);
				getLoggerId().set(IsolatableBuildOperationLogger.idOf(logger));
			}

			public BuildOperationLogger getLogger() {
				return IsolatableBuildOperationLogger.loggerOf(getLoggerId().get());
			}
		}

		private final ExecOperations execOperations;

		@Inject
		public CommandLineToolInvocationAction(ExecOperations execOperations) {
			this.execOperations = execOperations;
		}

		@Override
		public void execute() {
			// Copied from DefaultCommandLineToolInvocationWorker#execute
			StreamByteBuffer errOutput = new StreamByteBuffer();
			StreamByteBuffer stdOutput = new StreamByteBuffer();
			try {
				execOperations.exec(spec -> {
					spec.executable(getParameters().getExecutable().get());
					ProviderUtils.asJdkOptional(getParameters().getWorkDirectory()).ifPresent(it -> {
						try {
							Files.createDirectories(it.getAsFile().toPath());
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
						spec.workingDir(it);
					});

					spec.args(getParameters().getArgs().get());

					if (!getParameters().getPath().isEmpty()) {
						String pathVar = OperatingSystem.current().getPathVar();
						String toolPath = getParameters().getPath().getAsPath();
						toolPath = toolPath + File.pathSeparator + System.getenv(pathVar);
						spec.environment(pathVar, toolPath);
						if (OperatingSystem.current().isWindows()) {
							spec.getEnvironment().remove(pathVar.toUpperCase(Locale.ROOT));
						}
					}

					spec.environment(getParameters().getEnvironment().get());
					spec.setErrorOutput(errOutput.getOutputStream());
					spec.setStandardOutput(stdOutput.getOutputStream());
				});
				getParameters().getLogger().operationSuccess(getParameters().getDescription().get(), this.combineOutput(stdOutput, errOutput));
			} catch (ExecException e) {
				getParameters().getLogger().operationFailed(getParameters().getDescription().get(), this.combineOutput(stdOutput, errOutput));
				throw new RuntimeException(String.format("%s failed while %s.", getParameters().getName().get(), getParameters().getDescription().get()));
			}
		}

		private String combineOutput(StreamByteBuffer stdOutput, StreamByteBuffer errOutput) {
			return stdOutput.readAsString(Charset.defaultCharset()) + errOutput.readAsString(Charset.defaultCharset());
		}
	}

	//region Incremental rewrite for gradle/gradle#34152
	private transient IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler;

	@Internal
	public abstract Property<IncrementalCompilerBuilder> getIncrementalCompilerBuilderService();

	private IncrementalCompilerBuilder.IncrementalCompiler getIncrementalCompiler() {
		if (incrementalCompiler == null) {
			final IncrementalCompilerBuilder builder = getIncrementalCompilerBuilderService().getOrElse(getIncrementalCompilerBuilder());
			incrementalCompiler = builder.newCompiler(this, getSource(), getIncludes().plus(getSystemIncludes()), getMacros(), getToolChain().map(nativeToolChain -> nativeToolChain instanceof Gcc || nativeToolChain instanceof Clang));
		}
		return incrementalCompiler;
	}

	@Override
	protected final FileCollection getHeaderDependencies() {
		return getIncrementalCompiler().getHeaderFiles();
	}

	@Input // required because state version is typically assumed by the running Gradle version (not true for Nokee)
	protected Provider<Object> getCompileStateVersion() {
		return getIncrementalCompilerBuilderService().orElse(getIncrementalCompilerBuilder()).map(it -> {
			if (it instanceof DefaultIncrementalCompilerBuilder) {
				return ((DefaultIncrementalCompilerBuilder) it).currentStateVersion();
			} else {
				return it.getClass().getCanonicalName();
			}
		});
	}
	//endregion

	// For gradle/gradle#29492
	ConfigurableFileCollection source;

	private final ObjectFactory objects;
	private final WorkerExecutor executor;

	@Inject
	public CppCompileTask(ObjectFactory objects, ProviderFactory providers, WorkerExecutor executor) {
		this.objects = objects;
		this.executor = executor;
		this.source = super.getSource();

		replaceMacrosField(getMacros());

		getOptions().compilerArgs = getCompilerArgs();
		getOptions().allOptions = new SourceOptionsLookup() {
			@Override
			public Provider<NativeCompileOptions> get(File file) {
				return getAllSourceOptions().map(allOptions -> {
					return allOptions.forFile(file).getOptions();
				});
			}
		};
	}

	private interface SourceOptionsLookup {
		Provider<NativeCompileOptions> get(File file);
	}

	private void replaceMacrosField(Map<String, String> lazyMacros) {
		Field AbstractNativeCompileTask__macros = getField(AbstractNativeCompileTask.class, "macros");
		updateFieldValue(AbstractNativeCompileTask__macros, this, lazyMacros);
	}

	@Override
	@InputFiles
	@SkipWhenEmpty
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public ConfigurableFileCollection getSource() {
		return source;
	}

	@Override
	public void source(Object sourceFiles) {
		source.from(sourceFiles);
	}

	//region Per-source Options
	@Override
	public CppCompileTask source(Object source, Action<? super NativeCompileOptions> action) {
		SourceOptionsAware.super.source(source, action);
		return this;
	}
	//endregion

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		@Inject
		public Rule() {}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					project.getTasks().replace(compileTaskName(binary), CppCompileTask.class);
				});
			});
		}
	}
}
