package dev.nokee.companion.features;

import dev.nokee.commons.names.CppNames;
import dev.nokee.companion.CppBinaryObjects;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.nativeplatform.ComponentWithObjectFiles;

import javax.inject.Inject;

import static dev.nokee.companion.CppBinaryObjects.objectsOf;

/*private*/ final class ObjectsLifecycleTaskFeature implements Plugin<Project> {
	private final TaskContainer tasks;

	@Inject
	public ObjectsLifecycleTaskFeature(TaskContainer tasks) {
		this.tasks = tasks;
	}

	@Override
	public void apply(Project project) {
		project.getComponents().withType(CppBinary.class).configureEach(binary -> {
			tasks.register(CppNames.of(binary).taskName(it -> it.forObject("objects")).toString(), task -> {
				task.dependsOn(objectsOf(binary));
			});
		});
	}
}
