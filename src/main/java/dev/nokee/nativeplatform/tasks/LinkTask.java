package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.tasks.Nested;

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
}
