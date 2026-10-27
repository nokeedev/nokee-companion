package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.tasks.Nested;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;

/**
 * Links object files into a binary, for example shared library.
 */
public interface LinkTask extends Task {
	/**
	 * {@return the task options for this task}
	 */
	@Nested
	NativeLinkOptions getOptions();

	/**
	 * Configures the task options.
	 *
	 * @param action  the configure action
	 */
	default void options(Action<? super NativeLinkOptions> action) {
		action.execute(getOptions());
	}

	/**
	 * {@return the implementation type for a task that links an executable}
	 * It also implements {@link LinkTask}.
	 */
	@SuppressWarnings("unchecked")
	static Class<LinkExecutable> forExecutable() {
		return (Class<LinkExecutable>) (Class<?>) LinkExecutableTask.class;
	}

	/**
	 * {@return the implementation type for a task that links a shared library}
	 * It also implements {@link LinkTask}.
	 */
	@SuppressWarnings("unchecked")
	static Class<LinkSharedLibrary> forSharedLibrary() {
		return (Class<LinkSharedLibrary>) (Class<?>) LinkSharedLibraryTask.class;
	}
}
