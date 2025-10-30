package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppSharedLibrary;

import javax.inject.Inject;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.*;

/*private*/ abstract /*final*/ class OverlinkingAvoidanceFeature implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final TaskContainer tasks;

	@Inject
	public OverlinkingAvoidanceFeature(ConfigurationContainer configurations, TaskContainer tasks) {
		this.configurations = configurations;
		this.tasks = tasks;
	}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied("cpp-library", () -> {
			project.getComponents().withType(CppLibrary.class).configureEach(library -> {
				library.getBinaries().configureEach(CppSharedLibrary.class, binary -> {
					configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
						configuration.setExtendsFrom(configuration.getExtendsFrom().stream().filter(it -> !it.getName().endsWith(implementationConfigurationName(binary))).collect(Collectors.toList()));
						configuration.extendsFrom(configurations.getByName(apiConfigurationName(library)));
					});
				});
			});
		});

		Plugins.forProject(project).apply("native-companion.features.rpath-link-flags");
	}
}
