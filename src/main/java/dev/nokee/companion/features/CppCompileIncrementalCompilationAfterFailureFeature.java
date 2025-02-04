package dev.nokee.companion.features;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;

/*private*/ abstract /*final*/ class CppCompileIncrementalCompilationAfterFailureFeature implements Plugin<Project> {
	private final TaskContainer tasks;

	@Inject
	public CppCompileIncrementalCompilationAfterFailureFeature(TaskContainer tasks) {
		this.tasks = tasks;
	}

	@Override
	public void apply(Project project) {
		tasks.withType(CppCompileTask.class, task -> {
			task.getIncrementalAfterFailure().convention(true);
		});
	}
}
