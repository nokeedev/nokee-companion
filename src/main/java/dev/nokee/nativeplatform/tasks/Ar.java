package dev.nokee.nativeplatform.tasks;

import dev.nokee.work.EmitsWorkCompletionEvents;
import dev.nokee.work.WorkPlan;
import org.gradle.api.Incubating;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Archives object files into a static library.
 */
@EmitsWorkCompletionEvents
public interface Ar extends Task {
	/**
	 * {@return the work plan of this task, the archiving it is responsible for}
	 * The plan has no value when the task has no source.
	 */
	@WorkPlan
	@Internal
	@Incubating
	Provider<WorkItem> getWorkPlan();

	/**
	 * The archiving of an archive task, as a native tool invocation.
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
	 * {@return the implementation type for a task that creates a static library}
	 * It also implements {@link Ar}.
	 */
	@SuppressWarnings("unchecked")
	static Class<CreateStaticLibrary> forStaticLibrary() {
		return (Class<CreateStaticLibrary>) (Class<?>) ArTask.class;
	}
}
