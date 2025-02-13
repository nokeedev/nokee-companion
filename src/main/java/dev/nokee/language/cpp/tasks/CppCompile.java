package dev.nokee.language.cpp.tasks;

import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/**
 * Compiles C++ source files into object files.
 */
public abstract class CppCompile extends org.gradle.language.cpp.tasks.CppCompile {
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
	}
}
