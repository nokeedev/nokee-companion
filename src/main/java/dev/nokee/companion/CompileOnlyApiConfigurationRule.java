package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.names.CppNames;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.component.SoftwareComponentContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.language.cpp.CppLibrary;

import javax.inject.Inject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static dev.nokee.commons.names.CppNames.cppApiElementsConfigurationName;
import static dev.nokee.commons.names.CppNames.cppCompileConfigurationName;

/*private*/ abstract /*final*/ class CompileOnlyApiConfigurationRule implements Plugin<Project>, Rule {
	private final SoftwareComponentContainer components;
	private final ConfigurationContainer configurations;
	private final ConfigurationRegistry registry;

	@Inject
	public CompileOnlyApiConfigurationRule(SoftwareComponentContainer components, ConfigurationContainer configurations, ObjectFactory objects) {
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
		return "Pattern: <libraryName>CompileOnlyApi: Compile only API dependencies of a C++ library.";
	}

	@Override
	public void apply(String name) {
		for (CppLibrary library : components.withType(CppLibrary.class)) {
			String expectedName = CppNames.of(library).configurationName("compileOnlyApi").toString();
			if (name.equals(expectedName)) {
				NamedDomainObjectProvider<Configuration> compileOnly = registry.dependencyScope(name);
				compileOnly.configure(it -> it.setDescription(String.format("Compile only API dependencies for %s.", CppDisplayNames.describe(library))));
				configurations.named(cppApiElementsConfigurationName(library)).configure(extendsFrom(compileOnly));
				library.getBinaries().configureEach(binary -> {
					configurations.named(cppCompileConfigurationName(binary)).configure(extendsFrom(compileOnly));
				});
				compileOnly.get(); // must realize to make container happy
			}
		}
	}

	private static Action<Configuration> extendsFrom(Object... configurations) {
		return self -> {
			Deque<Object> queue = new ArrayDeque<>(Arrays.asList(configurations));
			while (!queue.isEmpty()) {
				Object next = queue.removeFirst();
				if (next instanceof Configuration) {
					self.extendsFrom((Configuration) next);
				} else if (next instanceof Provider) {
					queue.addFirst(((Provider<?>) next).get());
				} else {
					throw new IllegalArgumentException("Only Configuration or Provider of Configuration accepted.");
				}
			}
		};
	}
}
