package dev.nokee.language.nativebase.tasks.options;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;

/**
 * Preprocessor options for native compilation.
 *
 * @see NativeCompileOptions
 */
public interface PreprocessorOptions {
	/**
	 * {@return the defined macros for this options}
	 */
	@Input
	MapProperty<String, String> getDefinedMacros();
}
