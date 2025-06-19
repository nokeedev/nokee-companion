package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.toolchain.GccCompatibleToolChain;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				if (binary instanceof CppSharedLibrary || binary instanceof ComponentWithExecutable) {
					tasks.named(linkTaskName(binary), AbstractLinkTask.class).configure(new Action<AbstractLinkTask>() {
						@Override
						public void execute(AbstractLinkTask task) {
							Provider<Set<FileSystemLocation>> additionalDependencies = noRPathLinkSupport(task)
								.map(constant(Collections.<FileSystemLocation>emptySet()))
								.orElse(secondLevelDependencies());

							task.getInputs().files(additionalDependencies);
							task.getLinkerArgs().addAll(additionalDependencies.map(asRPathLinkArgs()));
						}

						private Provider<Set<FileSystemLocation>> secondLevelDependencies() {
							return configurations.named(nativeLinkConfigurationName(binary)).zip(configurations.named(nativeRuntimeConfigurationName(binary)), (linkLibraries, runtimeLibraries) -> {
								return runtimeLibraries.minus(linkLibraries).getElements();
							}).flatMap(it -> it);
						}

						private Provider<Object> noRPathLinkSupport(AbstractLinkTask task) {
							return task.getToolChain().zip(task.getTargetPlatform(), (toolchain, platform) -> (toolchain instanceof GccCompatibleToolChain && platform.getOperatingSystem().isLinux()) ? null : new Object());
						}

						private Transformer<List<String>, Set<FileSystemLocation>> asRPathLinkArgs() {
							return libraries -> {
								// Use relative path to avoid trashing the up-to-date checking and cacheability.
								return libraries.stream().map(it -> "-Wl,-rpath-link=" + project.getProjectDir().toPath().relativize(it.getAsFile().getParentFile().toPath())).collect(Collectors.toList());
							};
						}
					});
				}
			});
		});
	}

	private static <T> Transformer<T, Object> constant(T value) {
		return __ -> value;
	}
}
