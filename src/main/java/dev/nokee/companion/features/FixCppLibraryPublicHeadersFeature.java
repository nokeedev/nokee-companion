package dev.nokee.companion.features;

import dev.nokee.commons.names.CppNames;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppLibrary;

import javax.inject.Inject;
import java.io.File;
import java.util.concurrent.Callable;

import static dev.nokee.commons.names.CppNames.cppApiElementsConfigurationName;

/*private*/ abstract /*final*/ class FixCppLibraryPublicHeadersFeature implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final ObjectFactory objects;
	private final TaskContainer tasks;
	private final ProjectLayout layout;

	@Inject
	public FixCppLibraryPublicHeadersFeature(ConfigurationContainer configurations, ObjectFactory objects, TaskContainer tasks, ProjectLayout layout) {
		this.configurations = configurations;
		this.objects = objects;
		this.tasks = tasks;
		this.layout = layout;
	}

	@Override
	public void apply(Project project) {
		// because CppLibraryPlugin wait after project evaluation to configure plugin headers
		project.getPluginManager().withPlugin("cpp-library", ignored(() -> {
			project.afterEvaluate(ignored(() -> {
				project.getComponents().withType(CppLibrary.class).configureEach(library -> {
					configurations.named(cppApiElementsConfigurationName(library)).configure(apiElements -> {
						final Provider<File> exportedHeaders = project.provider(() -> {
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
						}).flatMap(it -> it);

						final Provider<File> publicHeader = library.getPublicHeaderDirs().getElements().map(files -> {
							if (files.isEmpty()) {
								throw new UnsupportedOperationException(String.format("The C++ library plugin currently requires at least one public header directory, however there are no directories configured."));
							} else if (files.size() == 1) {
								return files.iterator().next().getAsFile();
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
		}));
	}

	private static <T> Action<T> ignored(Runnable runnable) {
		return __ -> runnable.run();
	}
}
