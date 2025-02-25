package dev.nokee.language.cpp.tasks;

import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.language.nativebase.tasks.options.PreprocessorOptions;
import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import java.util.Map;

/**
 * Compiles C++ source files into object files.
 */
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
		getOptions().getPreprocessorOptions().getDefinedMacros().set(super.getMacros());
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

	/**
	 * {@return the task options for this task}
	 */
	@Nested
	public abstract Options getOptions();

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
		return dev.nokee.commons.gradle.provider.ProviderUtils.asJdkMap(getOptions().getPreprocessorOptions().getDefinedMacros());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setMacros(Map<String, String> macros) {
		getOptions().getPreprocessorOptions().getDefinedMacros().set(macros);
	}
	//endregion
}
