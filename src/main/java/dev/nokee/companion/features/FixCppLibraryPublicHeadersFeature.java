package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.names.CppNames;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppLibrary;

import javax.inject.Inject;
import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;

import static dev.nokee.commons.gradle.ActionUtils.ignored;
import static dev.nokee.commons.gradle.provider.ProviderUtils.flatten;
import static dev.nokee.commons.names.CppNames.cppApiElementsConfigurationName;

/*private*/ abstract /*final*/ class FixCppLibraryPublicHeadersFeature implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final TaskContainer tasks;
	private final ProjectLayout layout;
	private final ProviderFactory providers;

	@Inject
	public FixCppLibraryPublicHeadersFeature(ConfigurationContainer configurations, TaskContainer tasks, ProjectLayout layout, ProviderFactory providers) {
		this.configurations = configurations;
		this.tasks = tasks;
		this.layout = layout;
		this.providers = providers;
	}

	@Override
	public void apply(Project project) {
		// because CppLibraryPlugin wait after project evaluation to configure plugin headers
		Plugins.forProject(project).whenPluginApplied("cpp-library", () -> {
			project.afterEvaluate(ignored(() -> {
				project.getComponents().withType(CppLibrary.class).configureEach(library -> {
					configurations.named(cppApiElementsConfigurationName(library)).configure(apiElements -> {
						final Provider<File> exportedHeaders = flatten(providers.provider(() -> {
							final String taskName = CppNames.of(library).taskName("sync", "publicHeaders").toString();

							TaskProvider<Sync> syncTask = null;
							if (tasks.getNames().contains(taskName)) {
								syncTask = tasks.named(taskName, Sync.class);
							} else {
								syncTask = tasks.register(taskName, Sync.class, task -> {
									task.setDescription("Assemble the C++ API elements (e.g. public headers).");
									task.setDestinationDir(project.file(layout.getBuildDirectory().dir("exported-headers/" + library.getName())));
									task.from(library.getPublicHeaderDirs());
								});
							}
							return syncTask.map(Sync::getDestinationDir);
						}));

						final Provider<File> publicHeader = providers.provider(() -> {
							Set<File> files = library.getPublicHeaderDirs().getFiles();
							if (files.isEmpty()) {
								throw new UnsupportedOperationException("The C++ library plugin currently requires at least one public header directory, however there are no directories configured.");
							} else if (files.size() == 1) {
								return files.iterator().next();
							} else {
								return null; // force orElse
							}
						});

						final Provider<File> publicHeaders = publicHeader.orElse(exportedHeaders);
						apiElements.outgoing(outgoing -> {
							outgoing.getArtifacts().clear();
							outgoing.artifact(publicHeaders, artifact -> artifact.builtBy((Callable<?>) () -> publicHeader.isPresent() ? library.getPublicHeaderDirs() : exportedHeaders));
						});
					});
				});
			}));
		});
	}
}
