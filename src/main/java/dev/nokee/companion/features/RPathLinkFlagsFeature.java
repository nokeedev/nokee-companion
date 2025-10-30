package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;

import javax.inject.Inject;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.util.AvoidOverlinkingAction.avoidOverlinking;

/*private*/ abstract /*final*/ class RPathLinkFlagsFeature implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final TaskContainer tasks;

	@Inject
	public RPathLinkFlagsFeature(ConfigurationContainer configurations, TaskContainer tasks) {
		this.configurations = configurations;
		this.tasks = tasks;
	}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				if (binary instanceof CppSharedLibrary || binary instanceof ComponentWithExecutable) {
					tasks.named(linkTaskName(binary), AbstractLinkTask.class).configure(avoidOverlinking(configurations.named(nativeLinkConfigurationName(binary)), configurations.named(nativeRuntimeConfigurationName(binary))));
				}
			});
		});
	}
}
