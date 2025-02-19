package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.tasks.options.OptionsAware;
import dev.nokee.commons.gradle.tasks.options.SourceBucket;
import dev.nokee.commons.gradle.tasks.options.SourceOptions;
import dev.nokee.commons.gradle.tasks.options.SourceOptionsAware;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.internal.Cast;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeCompileSpec;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.compileTaskName;
import static dev.nokee.companion.features.TransactionalCompiler.outputFileDir;

@CacheableTask
/*private*/ abstract /*final*/ class CppCompileTask extends CppCompile implements OptionsAware, SourceOptionsAware<NativeCompileOptions> {
	public static abstract class DefaultTaskOptions implements OptionsAware.Options, CppCompile.Options, SourceOptionsAware.Options<NativeCompileOptions> {
		private final ListProperty<String> compilerArgs;

		@Inject
		public DefaultTaskOptions(ListProperty<String> compilerArgs) {
			this.compilerArgs = compilerArgs;
		}

		@Override
		public Provider<NativeCompileOptions> forSource(File file) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Provider<Iterable<SourceOptions<NativeCompileOptions>>> forAllSources() {
			throw new UnsupportedOperationException();
		}

		@Internal
		@Override
		public ListProperty<String> getCompilerArgs() {
			return compilerArgs;
		}
	}

	private final DefaultTaskOptions options;

	@Nested
	@Override
	public DefaultTaskOptions getOptions() {
		return options;
	}

	@Internal
	@Override
	public boolean isPositionIndependentCode() {
		return getOptions().getPositionIndependentCode().get();
	}

	@Override
	public void setPositionIndependentCode(boolean positionIndependentCode) {
		getOptions().getPositionIndependentCode().set(positionIndependentCode);
	}

	@Internal
	@Override
	public boolean isDebuggable() {
		return getOptions().getDebuggable().get();
	}

	@Override
	public void setDebuggable(boolean debug) {
		getOptions().getDebuggable().set(debug);
	}

	@Internal
	@Override
	public boolean isOptimized() {
		return getOptions().getOptimized().get();
	}

	@Override
	public void setOptimized(boolean optimize) {
		getOptions().getOptimized().set(optimize);
	}

	@Internal
	@Override
	public Map<String, String> getMacros() {
		return new AbstractMap<String, String>() {
			private final MapProperty<String, String> macros = getOptions().getPreprocessorOptions().getDefinedMacros();
			@Override
			public Set<Entry<String, String>> entrySet() {
				return macros.get().entrySet();
			}

			@Override
			public String getOrDefault(Object key, String defaultValue) {
				return macros.getting(key.toString()).getOrElse(defaultValue);
			}

			@Override
			public String get(Object key) {
				return macros.getting(key.toString()).getOrNull();
			}

			@Override
			public String put(String key, String value) {
				macros.put(key, value);
				return null; // can't honor the contract
			}

			@Override
			public void putAll(Map<? extends String, ? extends String> m) {
				macros.putAll(m);
			}

			@Override
			public Collection<String> values() {
				return macros.get().values();
			}

			@Override
			public Set<String> keySet() {
				return macros.keySet().get();
			}
		};
	}

	@Override
	public void setMacros(Map<String, String> macros) {
		getOptions().getPreprocessorOptions().getDefinedMacros().set(macros);
	}

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
		spec.setMacros(getOptions().getPreprocessorOptions().getDefinedMacros().get());
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
		Compiler<T> transactionalCompiler = perSourceCompiler;
		if (getIncrementalAfterFailure().get()) {
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

	@Internal
	public abstract Property<Boolean> getIncrementalAfterFailure();

	// For gradle/gradle#29492
	ConfigurableFileCollection source;

	// For header normalization on Windows
	FileCollection headerDependencies;

	@Inject
	public CppCompileTask(ObjectFactory objects, ProviderFactory providers) {
		this.source = super.getSource();
		this.headerDependencies = super.getHeaderDependencies();

		this.perSourceOptions = new AllSourceOptions<>(NativeCompileOptions.class, objects);

		getIncrementalAfterFailure().convention(false);
		this.options = objects.newInstance(DefaultTaskOptions.class, getCompilerArgs());
		getOptions().getDebuggable().convention(super.isDebuggable());
		getOptions().getOptimized().convention(super.isOptimized());
		getOptions().getPositionIndependentCode().convention(super.isPositionIndependentCode());
		getOptions().getPreprocessorOptions().getDefinedMacros().set(super.getMacros());

		getOptions().getBuckets().finalizeValueOnRead();
		getOptions().getBuckets().addAll(providers.provider(() -> {
			return perSourceOptions.entries.stream().map(it -> {
				return new SourceBucket() {
					@Input
					public Set<String> getFiles() {
						Set<String> result = new LinkedHashSet<>();
						it.sources.getAsFileTree().visit(new FileVisitor() {
							@Override
							public void visitDir(FileVisitDetails dirDetails) {
								// ignore
							}

							@Override
							public void visitFile(FileVisitDetails details) {
								result.add(details.getRelativePath().toString());
							}
						});
						return result;
					}

					@Nested
					public Action<?> getAction() {
						return it.configureAction;
					}
				};
			}).collect(Collectors.toList());
		}));
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

	@Override
	@InputFiles
	@Incremental
	@PathSensitive(PathSensitivity.NAME_ONLY)
	protected FileCollection getHeaderDependencies() {
		super.setMacros(options.getPreprocessorOptions().getDefinedMacros().get());
		return headerDependencies;
	}

	//region Per-source Options
	private final AllSourceOptions<NativeCompileOptions> perSourceOptions;

	@Nested // Required to track changes to the per-source options
	protected List<Action<?>> getSourceActions() {
		return perSourceOptions.entries.stream().map(it -> it.configureAction).collect(Collectors.toList());
	}

	// TODO: We may need to disallow lambda action. We should validate.
	@Override
	public CppCompileTask source(Object source, Action<? super NativeCompileOptions> action) {
		getSource().from(source);
		perSourceOptions.put(source, action);
		return this;
	}

	public static class AllSourceOptions<T> {
		// TODO: Use ActionSet
		/*private*/ final List<Entry<T>> entries = new ArrayList<>();
		private final Class<T> optionType;
		private final ObjectFactory objects;

		@Nullable
		public Key forFile(File file) {
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).contains(file)) {
					indices.add(i);
				}
			}

			if (indices.isEmpty()) {
				return null; // no per-source options (bucket 1)
			} else {
				// cacheable (bucket 2)
				return new Key(indices.stream().mapToInt(Integer::intValue).toArray());
			}
		}

		public class Key implements Iterable<Integer> {
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

			@Override
			public Iterator<Integer> iterator() {
				return Arrays.stream(indices).iterator();
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
			private final FileCollection sources;
			private Set<File> realizedFiles;
			private final Action<? super T> configureAction;

			public Entry(FileCollection sources, Action<? super T> configureAction) {
				this.sources = sources;
				this.configureAction = configureAction;
			}

			public boolean contains(File file) {
				if (realizedFiles == null) {
					realizedFiles = sources.getFiles();
				}
				return realizedFiles.contains(file);
			}
		}
	}
	//endregion

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		@Inject
		public Rule() {}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppComponent.class, component -> {
					component.getBinaries().whenElementKnown(CppBinary.class, binary -> {
						project.getTasks().replace(compileTaskName(binary), CppCompileTask.class);
					});
				});
			});
		}
	}
}
