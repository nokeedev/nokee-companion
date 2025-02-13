package dev.nokee.language.cpp.tasks;

import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import org.gradle.api.Action;

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
}
