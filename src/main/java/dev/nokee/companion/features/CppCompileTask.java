package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Factory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.file.SourceFile;
import dev.nokee.commons.gradle.file.SourceFileVisitor;
import dev.nokee.commons.gradle.tasks.options.*;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
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

import javax.inject.Inject;
import java.io.File;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

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

		@Internal
		@Override
		public ListProperty<String> getCompilerArgs() {
			return compilerArgs;
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

		NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) getToolChain().get();
		NativePlatformInternal nativePlatform = (NativePlatformInternal) getTargetPlatform().get();
		PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);

		setDidWork(doCompile(spec, platformToolProvider).getDidWork());
	}

	// Copied from AbstractNativeSourceCompileTask#doCompile with support for per-source options
	private <T extends NativeCompileSpec> WorkResult doCompile(T spec, PlatformToolProvider platformToolProvider) {
		Class<T> specType = Cast.uncheckedCast(spec.getClass());
		Compiler<T> baseCompiler = platformToolProvider.newCompiler(specType);

		PerSourceCompiler.SpecProvider specProvider = new PerSourceCompiler.SpecProvider() {
			@Override
			public ISourceKey forFile(File file) {
				return allOptions.keyOf(file).get();
			}
		};
		Compiler<T> perSourceCompiler = new PerSourceCompiler<>(baseCompiler, specProvider, () -> (T) createCompileSpec(), new PerSourceCompiler.SpecConfigure<T>() {
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

	// For header normalization on Windows
	FileCollection headerDependencies;

	@Inject
	public CppCompileTask(ObjectFactory objects, ProviderFactory providers) {
		this.source = super.getSource();
		this.headerDependencies = super.getHeaderDependencies();

		Factory<NativeCompileOptions> factory = () -> objects.newInstance(NativeCompileOptions.class);
		factory = factory.tap(t -> {
			t.getCompilerArgumentProviders().set(getOptions().getCompilerArgumentProviders());
			t.getCompilerArgs().set(getOptions().getCompilerArgs());
		});
		this.allOptions = objects.newInstance(new TypeOf<AllSourceOptionsEx2<NativeCompileOptions>>() {}.getConcreteClass(), factory);

		getBundles().set(providers.provider(() -> {
			List<Object> result = new ArrayList<>();
			Map<ISourceKey, Collection<SourceFile>> map = new LinkedHashMap<>();
			source.getAsFileTree().visit(new SourceFileVisitor(sourceFile -> {
				ISourceKey key = allOptions.keyOf(sourceFile.getFile()).get();
				if (key != ISourceKey.DEFAULT_KEY) { // must avoid capturing the default bucket
					map.computeIfAbsent(key, __ -> new ArrayList<>()).add(sourceFile);
				}
			}));

			map.forEach((key, files) -> {
				result.add(new Object() {
					@Input
					public Set<String> getPaths() {
						return files.stream().map(SourceFile::getPath).collect(Collectors.toSet());
					}

					@Nested
					public Object getOptions() {
						return allOptions.forKey(key).get();
					}
				});
			});
			return result;
		}));
		// MUST NOT finalizeValueOnRead() because the bundles may have
		getBundles().disallowChanges();

		getOptions().compilerArgs = getCompilerArgs();
		getOptions().allOptions = new SourceOptionsLookup<NativeCompileOptions>() {
			@Override
			public Provider<NativeCompileOptions> get(File file) {
				return allOptions.forFile(file).map(filter(it -> getSource().contains(file)));
			}

			@Override
			public Provider<Iterable<SourceOptions<NativeCompileOptions>>> getAll() {
				return allOptions.forAllSources(getSource().getAsFileTree()).map(OptionsIter::unrolled);
			}
		};

		// track build dependencies from source configuration
		//   TODO: Should it only be unmatched source configurations? But some configuration may be overshadowed by other.
		dependsOn((Callable<?>) allOptions.asProvider()::get);
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
		return headerDependencies;
	}

	//region Per-source Options
	@Nested // track bundle scope
	protected abstract ListProperty<Object> getBundles();

	private final AllSourceOptionsEx2<NativeCompileOptions> allOptions;

	@Override
	public AllSourceOptionsEx2<NativeCompileOptions> getSourceOptions() {
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
				project.getComponents().withType(CppComponent.class, component -> {
					component.getBinaries().whenElementKnown(CppBinary.class, binary -> {
						project.getTasks().replace(compileTaskName(binary), CppCompileTask.class);
					});
				});
			});
		}
	}
}
