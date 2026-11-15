package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.names.TaskName;
import dev.nokee.nativeplatform.tasks.Ar;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;
import org.gradle.language.plugins.NativeBasePlugin;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.qualifyingName;

/*private*/ abstract /*final*/ class ReplaceCreateTaskRule implements Plugin<Project> {
	@Inject
	public ReplaceCreateTaskRule() {}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(NativeBasePlugin.class, () -> {
			project.getComponents().withType(ComponentWithStaticLibrary.class).configureEach(binary -> {
				String taskName = TaskName.of("create").qualifiedBy(qualifyingName(binary)).toString();
				project.getTasks().replace(taskName, Ar.forStaticLibrary());
			});
		});
	}
}
