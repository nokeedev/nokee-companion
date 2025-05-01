package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Factory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.file.SourceFileVisitor;
import dev.nokee.commons.gradle.provider.ProviderUtils;
import dev.nokee.commons.gradle.tasks.options.*;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.*;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
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
import org.gradle.nativeplatform.toolchain.internal.*;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.process.ExecOperations;
import org.gradle.process.internal.ExecException;
import org.gradle.util.GradleVersion;
import org.gradle.work.InputChanges;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dev.nokee.commons.gradle.TransformerUtils.filter;
import static dev.nokee.commons.names.CppNames.compileTaskName;
import static dev.nokee.companion.features.ReflectionUtils.*;
import static dev.nokee.companion.features.TransactionalCompiler.outputFileDir;

@CacheableTask
/*private*/ abstract /*final*/ class CppCompileTask extends CppCompile implements OptionsAware, SourceOptionsAware<NativeCompileOptions> {
	public static abstract class DefaultTaskOptions implements dev.nokee.commons.gradle.tasks.options.Options, CppCompile.Options, SourceOptionsAware.Options<NativeCompileOptions> {
		private ListProperty<String> compilerArgs;
		private SourceOptionsLookup<NativeCompileOptions> allOptions;

		@Inject
		public DefaultTaskOptions() {}

		@Override
		public Provider<NativeCompileOptions> forSource(File file) {
			return allOptions.get(file);
		}

		@Override
		public Provider<Iterable<SourceOptions<NativeCompileOptions>>> forAllSources() {
			return allOptions.getAll();
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
					return definition == null ? null : definition.toString();
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
		spec.setOperationLogger(new IsolatableBuildOperationLogger(operationLogger));

		this.configureSpec(spec);

		NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) getToolChain().get();
		NativePlatformInternal nativePlatform = (NativePlatformInternal) getTargetPlatform().get();
		PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

		setDidWork(doCompile(spec, platformToolProvider).getDidWork());
	}

	// Copied from AbstractNativeSourceCompileTask#doCompile with support for per-source options
	private <T extends NativeCompileSpec> WorkResult doCompile(T spec, PlatformToolProvider platformToolProvider) {
		Class<T> specType = Cast.uncheckedCast(spec.getClass());
		Compiler<T> baseCompiler = platformToolProvider.newCompiler(specType);

		Compiler<T> perSourceCompiler = baseCompiler;
		if (allOptions != null) { // only if there are some per-options sources
			PerSourceCompiler.SpecProvider specProvider = new PerSourceCompiler.SpecProvider() {
				@Override
				public ISourceKey forFile(File file) {
					return allOptions.keyOf(file).get();
				}
			};
			WorkQueue queue = executor.noIsolation();
			perSourceCompiler = new PerSourceCompiler<>(baseCompiler, specProvider, () -> (T) createCompileSpec(), new PerSourceCompiler.SpecConfigure<T>() {
				@Override
				public void configureSpec(T spec, ISourceKey key) {
					NativeCompileOptions options = allOptions.forKey(key).get();

					// Namespace the temporary directory (i.e. where the options.txt will be written)
					spec.setTempDir(new File(spec.getTempDir(), hash(key)));

					// Configure the bucket spec from the per-source options
					spec.args(options.getCompilerArgs().get());
					for (CommandLineArgumentProvider argumentProvider : options.getCompilerArgumentProviders().get()) {
						argumentProvider.asArguments().forEach(spec.getArgs()::add);
					}
				}

				public String hash(ISourceKey key) {
					try {
						MessageDigest messageDigest = MessageDigest.getInstance("MD5");
						for (Integer i : (Iterable<Integer>) key) {
							messageDigest.update(ByteBuffer.allocate(4).putInt(i).array());
						}
						return new BigInteger(1, messageDigest.digest()).toString(36);
					} catch (NoSuchAlgorithmException e) {
						throw UncheckedException.throwAsUncheckedException(e);
					}
				}
			}, queue);

			// region Patching BuildOperationExecutor
			try {
				Compiler<T> compiler = baseCompiler;
				while (!(compiler instanceof AbstractCompiler)) {
					if (compiler instanceof VersionAwareCompiler) {
						compiler = readFieldValue(getField(compiler.getClass(), "compiler"), compiler);
					} else if (compiler instanceof OutputCleaningCompiler) {
						compiler = readFieldValue(getField(compiler.getClass(),"compiler"), compiler);
					}
				}

				updateFieldValue(getField(AbstractCompiler.class, "buildOperationExecutor"), compiler, objects.newInstance(WorkerBackedBuildOperationExecutor.class, queue));
				getLogger().debug("Patching the build operation executor was successful, enjoy light speed compilation!");
			} catch (Throwable e) {
				// do not patch... serial execution == slower
				getLogger().info("Could not patch the build operation executor, per-source option buckets will execute serially (aka slower).");
			}
			//endregion
		}

		Compiler<T> transactionalCompiler = perSourceCompiler;
		if (getOptions().getIncrementalAfterFailure().getOrElse(false) && spec.isIncrementalCompile()) {
			transactionalCompiler = new TransactionalCompiler<>(perSourceCompiler, outputFileDir(baseCompiler));
		}
		Compiler<T> incrementalCompiler = incrementalCompilerOf(this).createCompiler(transactionalCompiler);
		Compiler<T> loggingCompiler = BuildOperationLoggingCompilerDecorator.wrap(incrementalCompiler);
		return loggingCompiler.execute(spec);
	}

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
		private final UseCount count = new UseCount();

		private IsolatableBuildOperationLogger(BuildOperationLogger delegate) {
			this.delegate = delegate;
		}

		public static BuildOperationLogger loggerOf(String id) {
			return LOGGERS.get(id);
		}

		public static String idOf(BuildOperationLogger logger) {
			return logger.toString();
		}

		public static void incrementUsage(BuildOperationLogger logger) {
			((BuildOperationLoggerRef) logger).useCount().increment();
			LOGGERS.put(idOf(logger), logger);
		}

		public static void decrementUsage(BuildOperationLogger logger) {
			if (((BuildOperationLoggerRef) logger).useCount().decrement() == 0) {
				LOGGERS.remove(idOf(logger));
			}
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
		public UseCount useCount() {
			return count;
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
				IsolatableBuildOperationLogger.incrementUsage(logger);
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
				e.printStackTrace();
				getParameters().getLogger().operationFailed(getParameters().getDescription().get(), this.combineOutput(stdOutput, errOutput));
				throw new RuntimeException(String.format("%s failed while %s.", getParameters().getName().get(), getParameters().getDescription().get()));
			} finally {
				IsolatableBuildOperationLogger.decrementUsage(getParameters().getLogger());
			}
		}

		private String combineOutput(StreamByteBuffer stdOutput, StreamByteBuffer errOutput) {
			return stdOutput.readAsString(Charset.defaultCharset()) + errOutput.readAsString(Charset.defaultCharset());
		}
	}

	// We have to reach to AbstractNativeSourceCompileTask#incrementalCompiler
	private static IncrementalCompilerBuilder.IncrementalCompiler incrementalCompilerOf(AbstractNativeCompileTask self) {
		// On Gradle 8.11+, the `incrementalCompiler` field is transient and computed as needed via `getIncrementalCompiler()`
		if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) >= 0) {
			try {
				Method AbstractNativeCompileTask__getIncrementalCompiler = AbstractNativeCompileTask.class.getDeclaredMethod("getIncrementalCompiler");
				makeAccessible(AbstractNativeCompileTask__getIncrementalCompiler);
				return (IncrementalCompilerBuilder.IncrementalCompiler) AbstractNativeCompileTask__getIncrementalCompiler.invoke(self);
			} catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
				throw new RuntimeException(e);
			}
		} else { // On Gradle <8.11, we use `incrementalCompiler` field
			return readFieldValue(AbstractNativeCompileTask.class, "incrementalCompiler", self);
		}
	}

	// For gradle/gradle#29492
	ConfigurableFileCollection source;

	private final ObjectFactory objects;
	private final WorkerExecutor executor;

	@Inject
	public CppCompileTask(ObjectFactory objects, ProviderFactory providers, WorkerExecutor executor) {
		this.objects = objects;
		this.executor = executor;
		this.source = super.getSource();

		getBundles().set(providers.provider(() -> {
			if (this.allOptions == null) {
				return Collections.emptyList(); // bailout quickly
			}
			List<Object> result = new ArrayList<>();
			Map<ISourceKey, Collection<String>> map = new TreeMap<>(); // important to sort the keys...
			//  ...remember properties are flat hence it doesn't matter if a nested holder is a set, list or map.
			//  Everything will flatten into a non-hierarchical names, losing any concept of "unorderedness".
			//  Set vs List only take into effect for value snapshotting
			source.getAsFileTree().visit(new SourceFileVisitor(sourceFile -> {
				ISourceKey key = allOptions.keyOf(sourceFile.getFile()).get();
				if (key != ISourceKey.DEFAULT_KEY) { // must avoid capturing the default bucket
					map.computeIfAbsent(key, __ -> new ArrayList<>()).add(sourceFile.getPath());
				}
			}));

			map.forEach((key, files) -> {
				result.add(new Object() {
					@Input
					public Set<String> getPaths() {
						return new LinkedHashSet<>(files);
					}

					@Nested
					public Object getOptions() {
						return allOptions.forKey(key).get();
					}
				});
			});
			return result;
		}));
		getBundles().finalizeValueOnRead();
		getBundles().disallowChanges();

		getOptions().compilerArgs = getCompilerArgs();
		getOptions().allOptions = new SourceOptionsLookup<NativeCompileOptions>() {
			@Override
			public Provider<NativeCompileOptions> get(File file) {
				if (allOptions == null) {
					return providers.provider(() -> getOptions());
				}
				return allOptions.forFile(file).map(filter(it -> getSource().contains(file)));
			}

			@Override
			public Provider<Iterable<SourceOptions<NativeCompileOptions>>> getAll() {
				if (allOptions == null) {
					return providers.provider(() -> {
						return () -> new SourceOptionsIterator<>(getSource(), __ -> getOptions(), objects);
					});
				}
				return allOptions.forAllSources(getSource().getAsFileTree()).map(OptionsIter::unrolled);
			}
		};
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
	@Internal // track bundle scope
	protected abstract ListProperty<Object> getBundles();

	@Nested
	protected Iterable<Object> getSourceOptionsSnapshotting() {
		if (allOptions == null) {
			return Collections.emptyList();
		} else {
			for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
				if (stackTraceElement.getClassName().endsWith(".DefaultTaskProperties") && stackTraceElement.getMethodName().equals("resolve")) {
					return getBundles().get();
				} else if (stackTraceElement.getClassName().endsWith(".DefaultTaskInputs") && stackTraceElement.getMethodName().equals("visitDependencies")) {
					return allOptions.getDepEntries().get();
				}
			}
			throw new UnsupportedOperationException();
		}
	}

	private AllSourceOptionsEx2<NativeCompileOptions> allOptions;

	@Override
	public AllSourceOptionsEx2<NativeCompileOptions> getSourceOptions() {
		if (allOptions == null) {
			Factory<NativeCompileOptions> factory = () -> objects.newInstance(NativeCompileOptions.class);
			factory = factory.tap(t -> {
				t.getCompilerArgumentProviders().set(getOptions().getCompilerArgumentProviders());
				t.getCompilerArgs().set(getOptions().getCompilerArgs());
			});
			allOptions = objects.newInstance(new TypeOf<AllSourceOptionsEx2<NativeCompileOptions>>() {}.getConcreteClass(), factory);

		}
		return allOptions;
	}

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
