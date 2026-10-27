package dev.nokee.companion.features;

import dev.nokee.nativeplatform.tasks.LinkTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;

abstract class LinkAvoidanceFeature implements Plugin<Project> {
	@Inject
	public LinkAvoidanceFeature() {}

	@Inject protected abstract TaskContainer getTasks();

	@Override
	public void apply(Project project) {
		getTasks().withType(LinkTask.class).configureEach(task -> task.getLibrarySensitivity().set(LinkTask.LibrarySensitivity.LINK_ABI));
	}
}
