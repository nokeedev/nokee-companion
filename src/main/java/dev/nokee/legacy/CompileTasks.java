package dev.nokee.legacy;

import org.gradle.api.Action;
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

public abstract /*final*/ class CompileTasks {
	private final Set<String> knownElements = new HashSet<>();
	private final TaskContainer tasks;
	private final Provider<Set<? extends Task>> elementsProvider;

	@Inject
	public CompileTasks(TaskContainer tasks, ProviderFactory providers, ObjectFactory objects) {
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

	public void addLater(TaskProvider<? extends Task> compileTask) {
		knownElements.add(compileTask.getName());
	}

	public void configureEach(Action<? super Task> configureAction) {
		// TODO: Use commons actions
		tasks.withType(AbstractNativeCompileTask.class).configureEach(task -> {
			if (knownElements.contains(task.getName())) {
				configureAction.execute(task);
			}
		});
	}

	public <S extends Task> void configureEach(Class<S> type, Action<? super S> configureAction) {
		tasks.withType(type).configureEach(task -> {
			if (knownElements.contains(task.getName())) {
				configureAction.execute(task);
			}
		});
	}

	// for Java
	public static CompileTasks forBinary(CppBinary binary) {
		return ((ExtensionAware) binary).getExtensions().getByType(CompileTasks.class);
	}

	public Provider<Set<? extends Task>> getElements() {
		return elementsProvider;
	}

	/*private*/ static abstract /*final*/ class Rule extends FeaturePreviews.Plugin {
		private final TaskContainer tasks;

		@Inject
		public Rule(TaskContainer tasks) {
			super("compile-tasks-extension");
			this.tasks = tasks;
		}

		@Override
		protected void doApply(Project project) {
			project.getPlugins().withType(CppBasePlugin.class, ignored(() -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final CompileTasks compileTasks = ((ExtensionAware) binary).getExtensions().create("compileTasks", CompileTasks.class);
					compileTasks.addLater(tasks.named(compileTaskName(binary), CppCompile.class));
				});
			}));
		}

		private static <T> Action<T> ignored(Runnable runnable) {
			return __ -> runnable.run();
		}
	}
}
