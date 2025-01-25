package dev.nokee.legacy;

import dev.nokee.commons.backports.DependencyBucket;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.plugins.CppBasePlugin;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.implementationConfigurationName;

/*private*/ abstract /*final*/ class GradleIssueVersionCatalogueFix extends FeaturePreviews.Plugin {
	private final ConfigurationContainer configurations;
	private final ObjectFactory objects;

	@Inject
	public GradleIssueVersionCatalogueFix(ConfigurationContainer configurations, ObjectFactory objects) {
		super("fix-for-version-catalog");
		this.configurations = configurations;
		this.objects = objects;
	}

	@Override
	protected void doApply(Project project) {
		project.getPlugins().withType(CppBasePlugin.class, ignored(() -> {
			final DependencyBucket.Factory dependencyBucketFactory = objects.newInstance(DependencyBucket.Factory.class);

			project.getComponents().withType(CppComponent.class).configureEach(component -> {
				dependencyBucketFactory.create("implementation").asExtension(component.getDependencies()).of(component.getImplementationDependencies());
			});

			project.getComponents().withType(CppLibrary.class).configureEach(component -> {
				dependencyBucketFactory.create("api").asExtension(component.getDependencies()).of(component.getApiDependencies());
			});

			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				dependencyBucketFactory.create("implementation").asExtension(binary.getDependencies()).of(configurations.getByName(implementationConfigurationName(binary)));
			});
		}));
	}

	private static <T> Action<T> ignored(Runnable runnable) {
		return __ -> runnable.run();
	}
}
