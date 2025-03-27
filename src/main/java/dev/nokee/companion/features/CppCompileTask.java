package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Factory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.file.SourceFileVisitor;
import dev.nokee.commons.gradle.tasks.options.*;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.*;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.work.InputChanges;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static dev.nokee.commons.gradle.TransformerUtils.filter;
import static dev.nokee.commons.names.CppNames.compileTaskName;
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
		spec.setOperationLogger(operationLogger);

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
			});
		}

		Compiler<T> transactionalCompiler = perSourceCompiler;
		if (getOptions().getIncrementalAfterFailure().getOrElse(false)) {
			transactionalCompiler = new TransactionalCompiler<>(perSourceCompiler, outputFileDir(baseCompiler));
		}
		Compiler<T> incrementalCompiler = incrementalCompiler(this).createCompiler(transactionalCompiler);
		Compiler<T> loggingCompiler = BuildOperationLoggingCompilerDecorator.wrap(incrementalCompiler);
		return loggingCompiler.execute(spec);
	}

	// We have to reach to AbstractNativeSourceCompileTask#incrementalCompiler
	private static IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler(AbstractNativeCompileTask self) {
		try {
			Field AbstractNativeCompileTask__incrementalCompiler = AbstractNativeCompileTask.class.getDeclaredField("incrementalCompiler");
			AbstractNativeCompileTask__incrementalCompiler.setAccessible(true);
			return (IncrementalCompilerBuilder.IncrementalCompiler) AbstractNativeCompileTask__incrementalCompiler.get(self);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	// For gradle/gradle#29492
	ConfigurableFileCollection source;

	private final ObjectFactory objects;

	@Inject
	public CppCompileTask(ObjectFactory objects, ProviderFactory providers) {
		this.objects = objects;
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
