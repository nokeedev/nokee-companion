package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.names.CppNames;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Rule;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.component.SoftwareComponentContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.cpp.CppComponent;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.cppCompileConfigurationName;

/*private*/ abstract /*final*/ class CompileOnlyConfigurationRule implements Plugin<Project>, Rule {
	private final SoftwareComponentContainer components;
	private final ConfigurationContainer configurations;
	private final ConfigurationRegistry registry;

	@Inject
	public CompileOnlyConfigurationRule(SoftwareComponentContainer components, ConfigurationContainer configurations, ObjectFactory objects) {
		this.components = components;
		this.configurations = configurations;
		this.registry = objects.newInstance(ConfigurationRegistry.class);
	}

	@Override
	public void apply(Project project) {
		configurations.addRule(this);
	}

	@Override
	public String getDescription() {
		return "Pattern: <componentName>CompileOnly: Compile only dependencies of a C++ component.";
	}

	@Override
	public void apply(String name) {
		for (CppComponent component : components.withType(CppComponent.class)) {
			String expectedName = CppNames.of(component).configurationName("compileOnly").toString();
			if (name.equals(expectedName)) {
				NamedDomainObjectProvider<Configuration> compileOnly = registry.dependencyScope(name);
				compileOnly.configure(it -> it.setDescription(String.format("Compile only dependencies for %s.", CppDisplayNames.describe(component))));
				component.getBinaries().configureEach(binary -> {
					configurations.named(cppCompileConfigurationName(binary)).configure(it -> it.extendsFrom(compileOnly.get()));
				});
				compileOnly.get(); // must realize to make container happy
			}
		}
	}
}
