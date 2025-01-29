package dev.nokee.legacy;

import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;

abstract /*final*/ class GradleIssueIncrementalCompilationAfterFailureFix extends FeaturePreviews.Plugin {
	private final TaskContainer tasks;

	@Inject
	public GradleIssueIncrementalCompilationAfterFailureFix(TaskContainer tasks) {
		super("incremental-compilation-after-failure");
		this.tasks = tasks;
	}

	@Override
	protected void doApply(Project project) {
		tasks.withType(CppCompileTask.class, task -> {
			task.getIncrementalAfterFailure().convention(true);
		});
	}
}
