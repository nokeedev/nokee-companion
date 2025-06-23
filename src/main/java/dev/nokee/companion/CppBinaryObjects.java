package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithObjectFiles;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;

import javax.inject.Inject;
import java.util.concurrent.Callable;

import static dev.nokee.commons.gradle.TransformerUtils.traverse;

/**
 * Represents the shadow property for {@link CppBinary#getObjects()}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CppBinaryObjects {
	/**
	 * Returns the shadow property of {@link CppBinary#getObjects()}.
	 *
	 * @param binary  the binary with the objects
	 * @return the property
	 */
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#objectsOf"*/)
	public static ShadowProperty<FileCollection> objectsOf(ComponentWithObjectFiles binary) {
		return ShadowProperty.of(binary, "objects", binary::getObjects);
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private final ObjectFactory objects;
		private final CppEcosystemUtilities access;

		@Inject
		public Rule(ObjectFactory objects, Project project) {
			this.objects = objects;
			this.access = CppEcosystemUtilities.forProject(project);
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final ShadowProperty<FileCollection> allObjects = access.objectsOf(binary);
					final FileCollection objs = objects.fileCollection().from((Callable<?>) () -> {
						final CompileTasks compileTasks = (CompileTasks) ((ExtensionAware) binary).getExtensions().findByName("compileTasks");
						if (compileTasks != null) {
							return compileTasks.getElements().map(traverse(ObjectFiles::of));
						}
						return access.compileTaskOf(binary).map(ObjectFiles::of);
					});
					allObjects.set(objs);

					// Rewire the objects to account for the shadow property
					if (binary instanceof ComponentWithExecutable) {
						access.linkTaskOf((ComponentWithExecutable) binary).configure(task -> {
							task.getSource().setFrom(allObjects);
						});
					} else if (binary instanceof ComponentWithSharedLibrary) {
						access.linkTaskOf((ComponentWithSharedLibrary) binary).configure(task -> {
							task.getSource().setFrom(allObjects);
						});
					} else if (binary instanceof ComponentWithStaticLibrary) {
						access.createTaskOf((ComponentWithStaticLibrary) binary).configure(task -> {
							((ConfigurableFileCollection) task.getSource()).setFrom(allObjects);
						});
					}
				});
			});
		}
	}
}
