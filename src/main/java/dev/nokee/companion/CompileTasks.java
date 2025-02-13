package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

import static dev.nokee.commons.names.CppNames.compileTaskName;

/**
 * Represents a view of the compile tasks.
 * We do not impose any task type as compile task can come in all shape and size.
 * Use {@link #forBinary(CppBinary)} to retrieve an instance from Java code.
 */
public abstract /*final*/ class CompileTasks {
	/**
	 * Add a known task to this view
	 *
	 * @param compileTask a known compile task
	 */
	public abstract void addLater(TaskProvider<? extends Task> compileTask);

	/**
	 * Configures each compile tasks presently (and futurely) known to this view.
	 *
	 * @param configureAction  the configure action to execute
	 */
	public abstract void configureEach(Action<? super Task> configureAction);

	/**
	 * Configures each compile tasks presently (and futurely) known to this view of the specified type.
	 *
	 * @param type  the task type to configure
	 * @param configureAction  the configure action to execute
	 * @param <S>  the task type
	 */
	public abstract <S extends Task> void configureEach(Class<S> type, Action<? super S> configureAction);

	/**
	 * Returns the compile tasks view for the specified binary.
	 *
	 * @param binary  the binary to get the view for
	 * @return the compile tasks view
	 */
	public static CompileTasks forBinary(CppBinary binary) {
		return ((ExtensionAware) binary).getExtensions().getByType(CompileTasks.class);
	}

	/** {@return a live provider of all compile tasks of this view} */
	public abstract Provider<Set<? extends Task>> getElements();

	/*private*/ static abstract /*final*/ class CppBinaryCompileTasks extends CompileTasks {
		private final Set<String> knownElements = new HashSet<>();
		private final TaskContainer tasks;
		private final Provider<Set<? extends Task>> elementsProvider;

		@Inject
		public CppBinaryCompileTasks(TaskContainer tasks, ProviderFactory providers, ObjectFactory objects) {
			this.tasks = tasks;

			// TODO: Use commons
			this.elementsProvider = providers.provider(() -> {
				SetProperty<Task> result = objects.setProperty(Task.class);
				for (String name : knownElements) {
					result.add(tasks.named(name));
				}
				return result;
			}).flatMap(it -> it);
		}

		/**
		 * Add a known task to this view
		 *
		 * @param compileTask a known compile task
		 */
		public void addLater(TaskProvider<? extends Task> compileTask) {
			knownElements.add(compileTask.getName());
		}

		/**
		 * Configures each compile tasks presently (and futurely) known to this view.
		 *
		 * @param configureAction  the configure action to execute
		 */
		public void configureEach(Action<? super Task> configureAction) {
			// TODO: Use commons actions
			tasks.withType(AbstractNativeCompileTask.class).configureEach(task -> {
				if (knownElements.contains(task.getName())) {
					configureAction.execute(task);
				}
			});
		}

		/**
		 * Configures each compile tasks presently (and futurely) known to this view of the specified type.
		 *
		 * @param type  the task type to configure
		 * @param configureAction  the configure action to execute
		 * @param <S>  the task type
		 */
		public <S extends Task> void configureEach(Class<S> type, Action<? super S> configureAction) {
			tasks.withType(type).configureEach(task -> {
				if (knownElements.contains(task.getName())) {
					configureAction.execute(task);
				}
			});
		}

		/**
		 * Returns the compile tasks view for the specified binary.
		 *
		 * @param binary  the binary to get the view for
		 * @return the compile tasks view
		 */
		public static CompileTasks forBinary(CppBinary binary) {
			return ((ExtensionAware) binary).getExtensions().getByType(CompileTasks.class);
		}

		/** {@return a live provider of all compile tasks of this view} */
		public Provider<Set<? extends Task>> getElements() {
			return elementsProvider;
		}
	}

	/*private*/ static abstract /*final*/ class Feature implements Plugin<Project> {
		private final TaskContainer tasks;

		@Inject
		public Feature(TaskContainer tasks) {
			this.tasks = tasks;
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final CompileTasks compileTasks = ((ExtensionAware) binary).getExtensions().create("compileTasks", CppBinaryCompileTasks.class);
					compileTasks.addLater(tasks.named(compileTaskName(binary), CppCompile.class));
				});
			});
		}
	}
}
