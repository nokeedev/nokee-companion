package dev.nokee.legacy.features;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;

// See https://github.com/gradle/gradle/issues/29492
/*private*/ abstract /*final*/ class FixCppCompileRelativePathSensitivityFeature implements Plugin<Project> {
	private final TaskContainer tasks;
	private final ObjectFactory objects;

	@Inject
	public FixCppCompileRelativePathSensitivityFeature(TaskContainer tasks, ObjectFactory objects) {
		this.tasks = tasks;
		this.objects = objects;
	}

	@Override
	public void apply(Project project) {
		tasks.withType(CppCompileTask.class).configureEach(task -> {
			final ConfigurableFileCollection superSource = task.source;
			task.source = objects.fileCollection();
			superSource.from(task.source);
		});
	}
}
