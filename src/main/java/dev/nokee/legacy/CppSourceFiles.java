package dev.nokee.legacy;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.cpp.tasks.CppCompile;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.compileTaskName;

/**
 * Represents the shadow property for {@link CppBinary#getCppSource()} and {@link CppComponent#getCppSource()}.
 */
public final class CppSourceFiles {
	/**
	 * Returns the shadow property of {@link CppBinary#getCppSource()}.
	 *
	 * @param binary  the binary with C++ source.
	 * @return the property
	 */
	public static ShadowProperty<FileCollection> cppSourceOf(CppBinary binary) {
		return new ShadowProperty<>(binary, "cppSource", binary::getCppSource);
	}

	/**
	 * Returns the shadow property of {@link CppComponent#getCppSource()}.
	 *
	 * @param component  the component with C++ source.
	 * @return the property
	 */
	public static ShadowProperty<FileCollection> cppSourceOf(CppComponent component) {
		return new ShadowProperty<>(component, "cppSource", component::getCppSource);
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private final TaskContainer tasks;
		private final ObjectFactory objects;

		@Inject
		public Rule(TaskContainer tasks, ObjectFactory objects) {
			this.tasks = tasks;
			this.objects = objects;
		}

		@Override
		public void apply(Project project) {
			project.getPlugins().withType(CppBasePlugin.class, ignored(() -> {
				project.getComponents().withType(CppComponent.class).configureEach(component -> {
					component.getBinaries().configureEach(binary -> {
						cppSourceOf(binary).set(objects.fileCollection().from(cppSourceOf(component)));
					});
				});
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					tasks.named(compileTaskName(binary), CppCompile.class).configure(task -> {
						try {
							task.getSource().setFrom(cppSourceOf(binary));
						} catch (IllegalStateException e) {
							// We only log the failure as the `cppSource` may be wired through a different process
							//   See per-source file compiler args sample.
							project.getLogger().warn(String.format("Could not wire shadowed 'cppSource' from C++ binary '%s' in %s to %s.", binary.getName(), project, task));
						}
					});
				});
			}));
		}

		private static <T> Action<T> ignored(Runnable runnable) {
			return __ -> runnable.run();
		}
	}
}
