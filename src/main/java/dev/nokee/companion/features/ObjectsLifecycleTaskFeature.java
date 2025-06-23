package dev.nokee.companion.features;

import dev.nokee.commons.names.CppNames;
import dev.nokee.companion.CppEcosystemUtilities;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;

import javax.inject.Inject;

/*private*/ final class ObjectsLifecycleTaskFeature implements Plugin<Project> {
	private final TaskContainer tasks;
	private final CppEcosystemUtilities access;

	@Inject
	public ObjectsLifecycleTaskFeature(TaskContainer tasks, Project project) {
		this.tasks = tasks;
		this.access = CppEcosystemUtilities.forProject(project);
	}

	@Override
	public void apply(Project project) {
		project.getComponents().withType(CppBinary.class).configureEach(binary -> {
			tasks.register(CppNames.of(binary).taskName(it -> it.forObject("objects")).toString(), task -> {
				task.dependsOn(access.objectsOf(binary));
			});
		});
	}
}
