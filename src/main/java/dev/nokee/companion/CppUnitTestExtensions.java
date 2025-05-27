package dev.nokee.companion;

import dev.nokee.commons.backports.DependencyFactory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.attributes.Attributes;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.HasConfigurableAttributes;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.reflect.TypeOf;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.nokee.commons.gradle.provider.ProviderUtils.asJdkOptional;
import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryProperties.compileIncludePathOf;
import static dev.nokee.companion.CppBinaryTaskExtensions.compileTask;

/**
 * Represents missing properties that matches the tested component/binary for C++ test suites.
 */
public final class CppUnitTestExtensions {
	/**
	 * Returns a property to configure the tested component of a C++ test suite.
	 * Note that this property is distinct from Gradle's internal property of the same name.
	 * When using the native companion plugins, use this property instead!
	 *
	 * @param testSuite  the test suite
	 * @return the tested component property
	 */
	@SuppressWarnings("unchecked")
	public static Property<ProductionCppComponent> testedComponentOf(CppTestSuite testSuite) {
		return (Property<ProductionCppComponent>) ((ExtensionAware) testSuite).getExtensions().getByName("testedComponent");
	}

	@SuppressWarnings("UnstableApiUsage")
	static abstract /*final*/ class Rule implements Plugin<Project> {
		private static final String TESTED_COMPONENT_EXTENSION_NAME = "testedComponent";

		private final ObjectFactory objects;
		private final ProviderFactory providers;
		private final ConfigurationContainer configurations;
		private final DependencyFactory dependencyFactory;

		@Inject
		public Rule(ObjectFactory objects, ProviderFactory providers, ConfigurationContainer configurations) {
			this.objects = objects;
			this.providers = providers;
			this.configurations = configurations;
			this.dependencyFactory = objects.newInstance(DependencyFactory.class);
		}

		@Override
		public void apply(Project project) {
			project.getPluginManager().apply(CppBinaryProperties.Rule.class);
			project.getPluginManager().apply(NativeArtifactTypeDefinition.Rule.class);

			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					Property<ProductionCppComponent> testedComponent = objects.property(ProductionCppComponent.class);
					((ExtensionAware) testSuite).getExtensions().add(new TypeOf<Property<ProductionCppComponent>>() {}, TESTED_COMPONENT_EXTENSION_NAME, testedComponent);
					testedComponent.convention(providers.provider(() -> {
						if (project.getPlugins().hasPlugin("cpp-application")) {
							return project.getExtensions().getByType(CppApplication.class);
						} else if (project.getPlugins().hasPlugin("cpp-library")) {
							return project.getExtensions().getByType(CppLibrary.class);
						} else {
							return null;
						}
					}));

					configurations.named(implementationConfigurationName(testSuite)).configure(configuration -> {
						configuration.withDependencies(dependencies -> {
							if (dependencies.stream().flatMap(it -> dependencyProjectPathOf(it).map(Stream::of).orElseGet(Stream::empty)).noneMatch(project.getPath()::equals)) {
								asJdkOptional(testedComponent).ifPresent(component -> {
									// Note: At one point we will have to support a different component then the main component
									//   For now, we assume the main component.
									assert component.getName().equals("main") : "'testedComponent' must be the main component";

									dependencies.add(dependencyFactory.create(project).attributes(attributes -> attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))));
								});
							}
						});
					});
				});

				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					// Detach compileIncludePath from testedComponent
					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
						final FileCollection includeDirs = objects.fileCollection().from((Callable<?>) () -> {
							ArtifactView view = configurations.getByName(cppCompileConfigurationName(testExecutable)).getIncoming().artifactView(attributes(objects, details -> {
								details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
							}));
							return view.getFiles();
						});
						final ShadowProperty<FileCollection> compileIncludePath = compileIncludePathOf(testExecutable);
						compileIncludePath.set(testSuite.getPrivateHeaderDirs().plus(includeDirs));
						compileTask(testExecutable).configure(task -> {
							task.getIncludes().setFrom(compileIncludePath);
						});
					});

					// Detach implementation configuration from testedComponent
					testSuite.getBinaries().whenElementFinalized(CppTestExecutable.class, testExecutable -> {
						configurations.named(implementationConfigurationName(testExecutable)).configure(it -> {
							it.setExtendsFrom(it.getExtendsFrom().stream().filter(t -> !(t.getName().startsWith("main") && t.getName().endsWith("Implementation"))).collect(Collectors.toList()));
						});
					});

					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
						// Detach testedObjects from nativeLinkTest
						// Assuming a single FileCollectionDependency which should be the Gradle core object files.
						//   In cases where this code executes **before** normal Gradle code (for some reason),
						//   we can remove the Gradle (previous) testableObjects dependency to replace it with our own.
						//   Note that we should normally be able to inspect the dependencies directly via:
						//     nativeLink.getDependencies().removeIf(it -> it instanceof FileCollectionDependency)
						//   Instead, we remove any current and future, FileCollectionDependency.
						final Configuration nativeLink = configurations.getByName(nativeLinkConfigurationName(testExecutable));
						nativeLink.getDependencies().all(dependencyCandidate -> {
							if (dependencyCandidate instanceof FileCollectionDependency) {
								nativeLink.getDependencies().remove(dependencyCandidate);
							}
						});
					});
				});
			});
		}
	}

	private static Optional<String> dependencyProjectPathOf(Dependency dependency) {
		if (dependency instanceof ProjectDependency) {
			if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) >= 0) {
				return Optional.of(((ProjectDependency) dependency).getPath());
			} else {
				return Optional.of(((ProjectDependency) dependency).getDependencyProject().getPath());
			}
		}
		return Optional.empty();
	}

	// TODO: Move to Attributes
	private static <T> Action<T> attributes(ObjectFactory objects, Action<? super Attributes.Details> action) {
		return configuration -> {
			if (configuration instanceof HasConfigurableAttributes) {
				objects.newInstance(Attributes.Extension.class).of((HasConfigurableAttributes<?>) configuration, action);
			} else {
				throw new UnsupportedOperationException();
			}
		};
	}
}
