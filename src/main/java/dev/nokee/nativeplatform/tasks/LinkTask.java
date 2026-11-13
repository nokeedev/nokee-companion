package dev.nokee.nativeplatform.tasks;

import dev.nokee.work.EmitsWorkCompletionEvents;
import dev.nokee.work.WorkPlan;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Task;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Links object files into a binary, for example shared library.
 */
@EmitsWorkCompletionEvents
public interface LinkTask extends Task {
	/**
	 * {@return the work plan of this task, the link it is responsible for}
	 * The plan has no value when the task has no source.
	 */
	@WorkPlan
	@Internal
	@Incubating
	Provider<WorkItem> getWorkPlan();

	/**
	 * The link of a link task, as a native tool invocation.
	 *
	 * <p>Each item is self-contained: it carries the files the invocation reads besides the task inputs, the working directory, the environment and the tool path.
	 * Running an item consists of writing its {@linkplain #getFiles() files}, creating its {@linkplain #getWorkingDirectory() working directory},
	 * prepending its {@linkplain #getPath() path entries} to the {@code PATH} environment variable, applying its {@linkplain #getEnvironment() environment},
	 * then executing its {@linkplain #getExecutable() executable} with its {@linkplain #getArguments() arguments}.</p>
	 *
	 * <p>Items are immutable and serializable.</p>
	 */
	@Incubating
	interface WorkItem {
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
