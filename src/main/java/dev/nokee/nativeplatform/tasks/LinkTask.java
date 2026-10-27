package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
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
	 * {@return the property to configure what this task observes about its link libraries}
	 */
	@Internal
	Property<LibrarySensitivity> getLibrarySensitivity();

	/**
	 * Which part of a library a link task observes.
	 * A library change outside what the task observes leaves the task up-to-date.
	 */
	enum LibrarySensitivity {
		/**
		 * The whole library file, byte-for-byte.
		 */
		CONTENT,

		/**
		 * The library's linker-facing representation.
		 * See the user manual for more information.
		 */
		LINK_ABI;

		LinkAbiAware.AbiSnapshotter toSnapshotter() {
			switch (this) {
				case CONTENT: return LinkAbiAware.AbiSnapshotter.NONE;
				case LINK_ABI: return LinkAbiAware.AbiSnapshotter.FULL_ABI;
				default: throw new UnsupportedOperationException("unhandled library sensitivity '" + this + "'");
			}
		}
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
