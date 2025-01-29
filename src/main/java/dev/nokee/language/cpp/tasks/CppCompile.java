package dev.nokee.language.cpp.tasks;

import org.gradle.api.Action;
import org.gradle.api.provider.ListProperty;

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
	public abstract CppCompile source(Object sourceFiles, Action<? super Options> configureAction);

	/**
	 * Compile options for C++ compilation.
	 */
	public interface Options {
		/**
		 * Additional arguments to provide to the compiler.
		 *
		 * @return a property to configure the additional arguments
		 */
		ListProperty<String> getCompilerArgs();
	}
}
