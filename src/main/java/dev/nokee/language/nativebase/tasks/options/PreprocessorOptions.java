package dev.nokee.language.nativebase.tasks.options;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Preprocessor options for native compilation.
 *
 * @see NativeCompileOptions
 */
public interface PreprocessorOptions {
	/**
	 * Define a name only macro.
	 *
	 * @param name  the macro name
	 */
	void define(String name);

	/**
	 * Define a macro with a definition.
	 *
	 * @param name  the macro name
	 * @param definition  the macro definition
	 */
	void define(String name, Object definition);

	/**
	 * Defines multiple macros where each entry is a macro with it's optional definition.
	 *
	 * @param definedMacros  the macros to define
	 */
	void defines(Map<? extends String, ? extends Object> definedMacros);

	/**
	 * Defines multiple macros where the content can be either a Map or an Iterable to {@code DefinedMacro}.
	 *
	 * @param definedMacros  the macros to define
	 */
	void defines(Provider<? extends Object> definedMacros);

	/**
	 * {@return the defined macros for this options}
	 */
	@Nested
	ListProperty<DefinedMacro> getDefinedMacros();

	/**
	 * Represent a defined macro.
	 */
	interface DefinedMacro {
		/**
		 * {@return the macro name}
		 */
		@Input
		String getName();

		/**
		 * {@return the macro definition is present or {@literal null}}
		 */
		@Input
		@Optional
		@Nullable
		String getDefinition();
	}
}
