package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

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
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#cppSourceOf"*/)
	public static ShadowProperty<FileCollection> cppSourceOf(CppBinary binary) {
		return ShadowProperty.of(binary, "cppSource", binary::getCppSource);
	}

	/**
	 * Returns the shadow property of {@link CppComponent#getCppSource()}.
	 *
	 * @param component  the component with C++ source.
	 * @return the property
	 */
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#cppSourceOf"*/)
	public static ShadowProperty<FileCollection> cppSourceOf(CppComponent component) {
		return ShadowProperty.of(component, "cppSource", component::getCppSource);
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
				Map<String, ShadowProperty<FileCollection>> cppSourceByComponents = new HashMap<>();
				project.getComponents().withType(CppComponent.class).configureEach(component -> {
					cppSourceByComponents.put(component.getName(), access.cppSourceOf(component));
				});
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					String componentName = cppSourceByComponents.keySet().stream().filter(it -> binary.getName().startsWith(it)).findFirst().orElseThrow(RuntimeException::new);
					ShadowProperty<FileCollection> cppSourceOfComponent = cppSourceByComponents.get(componentName);
					access.cppSourceOf(binary).set(objects.fileCollection().from(cppSourceOfComponent));
				});
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					access.compileTaskOf(binary).configure(task -> {
						try {
							// Note: We are **not** using `setFrom` as some projects configures generated source files through Project:
							//   project.tasks.withType(CppCompile).configureEach { source(...) }
							task.getSource().from(access.cppSourceOf(binary));
						} catch (IllegalStateException e) {
							// We only log the failure as the `cppSource` may be wired through a different process
							//   See per-source file compiler args sample.
							project.getLogger().warn(String.format("Could not wire shadowed 'cppSource' from C++ binary '%s' in %s to %s.", binary.getName(), project, task));
						}
					});
				});
			});
		}
	}
}
