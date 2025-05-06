package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithLinkFile;
import org.gradle.language.nativeplatform.ComponentWithRuntimeFile;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

abstract /*final*/ class CppBinaryConfigurationRule implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final ObjectFactory objects;

	@Inject
	public CppBinaryConfigurationRule(ConfigurationContainer configurations, ObjectFactory objects) {
		this.configurations = configurations;
		this.objects = objects;
	}

	@SuppressWarnings("UnstableApiUsage")
	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				configurations.named(cppCompileConfigurationName(binary)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
					});
				});

				if (binary instanceof ComponentWithLinkFile || binary instanceof ComponentWithExecutable) {
					configurations.named(nativeLinkConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						});
					});
				}

				if (binary instanceof ComponentWithRuntimeFile || binary instanceof ComponentWithExecutable) {
					configurations.named(nativeRuntimeConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						});
					});
				}
			});

			project.getComponents().withType(CppLibrary.class).configureEach(component -> {
				configurations.named(cppApiElementsConfigurationName(component)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
					});
				});
			});
		});
	}
}
