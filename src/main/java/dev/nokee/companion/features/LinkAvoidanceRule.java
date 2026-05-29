package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.names.TaskName;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.plugins.NativeBasePlugin;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;

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
				project.getTasks().replace(taskName, linkExecutableType());
			});
			project.getComponents().withType(ComponentWithSharedLibrary.class).configureEach(binary -> {
				project.getTasks().replace(TaskName.of("link").qualifiedBy(qualifyingName(binary)).toString(), linkSharedLibraryType());
			});
		});
	}

	@SuppressWarnings("unchecked")
	private static Class<? extends LinkSharedLibrary> linkSharedLibraryType() {
		try {
			return (Class<LinkSharedLibrary>) Class.forName("dev.nokee.nativeplatform.tasks.LinkSharedLibraryTask");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Class<? extends LinkExecutable> linkExecutableType() {
		try {
			return (Class<LinkExecutable>) Class.forName("dev.nokee.nativeplatform.tasks.LinkExecutableTask");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
}
