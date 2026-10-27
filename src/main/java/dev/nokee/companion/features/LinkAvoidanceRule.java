package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.names.TaskName;
import dev.nokee.nativeplatform.tasks.LinkTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.plugins.NativeBasePlugin;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.qualifyingName;

/*private*/ abstract /*final*/ class LinkAvoidanceRule implements Plugin<Project> {
	@Inject
	public LinkAvoidanceRule() {}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(NativeBasePlugin.class, () -> {
			project.getComponents().withType(ComponentWithExecutable.class).configureEach(binary -> {
				String taskName = TaskName.of("link").qualifiedBy(qualifyingName(binary)).toString();
				project.getTasks().replace(taskName, LinkTask.forExecutable());
			});
			project.getComponents().withType(ComponentWithSharedLibrary.class).configureEach(binary -> {
				String taskName = TaskName.of("link").qualifiedBy(qualifyingName(binary)).toString();
				project.getTasks().replace(taskName, LinkTask.forSharedLibrary());
			});
		});
	}
}
