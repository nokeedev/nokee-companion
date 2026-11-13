package dev.nokee.language.cpp.tasks;

import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import dev.nokee.work.EmitsWorkCompletionEvents;
import dev.nokee.work.WorkPlan;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

/**
 * Compiles C++ source files into object files.
 */
@EmitsWorkCompletionEvents
public abstract class CppCompile extends org.gradle.language.cpp.tasks.CppCompile {
	/**
	 * {@return implementation type for this task to using when registering adhoc task}
	 */
	@SuppressWarnings("unchecked")
	public static Class<CppCompile> clazz() {
		try {
			return (Class<CppCompile>) Class.forName("dev.nokee.companion.features.CppCompileTask");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	protected CppCompile() {
		getOptions().getDebuggable().convention(super.isDebuggable());
		getOptions().getOptimized().convention(super.isOptimized());
		getOptions().getPositionIndependentCode().convention(super.isPositionIndependentCode());
		getOptions().getPreprocessorOptions().defines(super.getMacros());
	}

	/**
	 * Adds a set of source files to compile with a specific compile options.
	 * The compile options configured by the action will apply to all source specified.
	 * The provided sourceFiles object is evaluated as per {@link org.gradle.api.Project#files(Object...)}.
	 *
	 * @param sourceFiles  the source files to compile
	 * @param configureAction  an action to configure per-source compile options
	 * @return this task
	 */
	public abstract CppCompile source(Object sourceFiles, Action<? super NativeCompileOptions> configureAction);

	public abstract CppCompile options(Spec<? super File> sourceSpec, Action<? super NativeCompileOptions> configureAction);

	/**
	 * {@return the task options for this task}
	 */
	@Nested
	public abstract Options getOptions();

	/**
	 * {@return the work plan of this task, one work item per compilation}
	 */
	@WorkPlan
	@Internal
	@Incubating
	public abstract Provider<List<WorkItem>> getWorkPlan();

	/**
	 * A compilation of a compile task, as a native tool invocation.
	 *
	 * <p>Each item is self-contained: it carries the files the invocation reads besides the task inputs, the working directory, the environment and the tool path.
	 * Running an item consists of writing its {@linkplain #getFiles() files}, creating its {@linkplain #getWorkingDirectory() working directory},
	 * prepending its {@linkplain #getPath() path entries} to the {@code PATH} environment variable, applying its {@linkplain #getEnvironment() environment},
	 * then executing its {@linkplain #getExecutable() executable} with its {@linkplain #getArguments() arguments}.
	 * Items of the same plan are independent of each other.</p>
	 *
	 * <p>Items are immutable and serializable.</p>
	 */
	@Incubating
	public interface WorkItem {
		/**
		 * {@return the absolute path of the tool to execute}
		 */
		String getExecutable();

		/**
		 * {@return the arguments of the tool, as executed}
		 * Options files referenced by an argument, such as {@code @options.txt}, are not expanded; their content is available via {@link #getFiles()}.
		 */
		List<String> getArguments();

		/**
		 * {@return the content of each options file referenced by the arguments, keyed by absolute path}
		 */
		Map<String, String> getFiles();

		/**
		 * {@return the working directory of the invocation, or null for the current directory}
		 */
		@Nullable
		File getWorkingDirectory();

		/**
		 * {@return the environment variables of the invocation, in addition to the inherited environment}
		 */
		Map<String, String> getEnvironment();

		/**
		 * {@return the directories prepended to the {@code PATH} environment variable of the invocation}
		 */
		List<File> getPath();
	}

	/**
	 * Compile options for C++ compilation.
	 */
	public interface Options extends NativeCompileOptions {
		/**
		 * {@return the property to configure the optimization for all compilation units}
		 */
		@Input
		Property<Boolean> getOptimized();

		/**
		 * {@return the property to configure the debuggability for all compilation units}
		 */
		@Input
		Property<Boolean> getDebuggable();

		/**
		 * {@return the property to configure the position independence for all compilation units}
		 */
		@Input
		Property<Boolean> getPositionIndependentCode();

		/**
		 * {@return the preprocessor options for all compilation units}
		 */
		@Nested
		PreprocessorOptions getPreprocessorOptions();

		/**
		 * {@return the property to configure incremental compilation after a failure}
		 */
		@Input
		@Optional
		Property<Boolean> getIncrementalAfterFailure();

		/**
		 * Returns the compile options for the specified C++ compilation unit.
		 *
		 * @param sourceFile  the C++ compilation unit
		 * @return a provider to the compile options of a source file
		 */
		Provider<NativeCompileOptions> forSource(File sourceFile);
	}

	//region Legacy Properties
	/**
	 * {@inheritDoc}
	 */
	@Internal
	@Override
	public boolean isPositionIndependentCode() {
		return getOptions().getPositionIndependentCode().get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setPositionIndependentCode(boolean positionIndependentCode) {
		getOptions().getPositionIndependentCode().set(positionIndependentCode);
	}

	/**
	 * {@inheritDoc}
	 */
	@Internal
	@Override
	public boolean isDebuggable() {
		return getOptions().getDebuggable().get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setDebuggable(boolean debug) {
		getOptions().getDebuggable().set(debug);
	}

	/**
	 * {@inheritDoc}
	 */
	@Internal
	@Override
	public boolean isOptimized() {
		return getOptions().getOptimized().get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setOptimized(boolean optimize) {
		getOptions().getOptimized().set(optimize);
	}

	/**
	 * {@inheritDoc}
	 */
	@Internal
	@Override
	public Map<String, String> getMacros() {
		class MacroMap extends AbstractMap<String, String> {
			private final ListProperty<PreprocessorOptions.DefinedMacro> delegate = getOptions().getPreprocessorOptions().getDefinedMacros();

			public String remove(Object key) {
				List<PreprocessorOptions.DefinedMacro> newValues = new ArrayList<>(delegate.get());

				try {
					ListIterator<PreprocessorOptions.DefinedMacro> iter = newValues.listIterator();
					while (iter.hasNext()) {
						PreprocessorOptions.DefinedMacro macro = iter.next();
						if (macro.getName().equals(key)) {
							iter.remove();
							return macro.getDefinition();
						}
					}
					return null;
				} finally {
					delegate.set(newValues);
				}
			}

			public void clear() {
				this.delegate.empty();
			}

			public Set<Entry<String, String>> entrySet() {
				return asMap().entrySet();
			}

			public String getOrDefault(Object key, String defaultValue) {
				for (PreprocessorOptions.DefinedMacro macro : delegate.get()) {
					if (macro.getName().equals(key)) {
						return macro.getDefinition();
					}
				}
				return defaultValue;
			}

			public String get(Object key) {
				return getOrDefault(key, null);
			}

			public String put(String key, String value) {
				if (value == null) {
					getOptions().getPreprocessorOptions().define(key);
				} else {
					getOptions().getPreprocessorOptions().define(key, value);
				}
				return null;
			}

			public void putAll(Map<? extends String, ? extends String> m) {
				getOptions().getPreprocessorOptions().defines(m);
			}

			public Collection<String> values() {
				return asMap().values();
			}

			public Set<String> keySet() {
				return asMap().keySet();
			}

			private Map<String, String> asMap() {
				Map<String, String> result = new LinkedHashMap<>();
				for (PreprocessorOptions.DefinedMacro macro : delegate.get()) {
					result.put(macro.getName(), macro.getDefinition());
				}
				return result;
			}
		}
		return new MacroMap();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setMacros(Map<String, String> macros) {
		getOptions().getPreprocessorOptions().getDefinedMacros().empty();
		getOptions().getPreprocessorOptions().defines(macros);
	}
	//endregion
}
