package dev.nokee.companion;

import dev.nokee.commons.backports.DependencyFactory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.provider.ZipProvider;
import dev.nokee.companion.util.TestedBinaryMapper;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.nativeplatform.tasks.UnexportMainSymbol;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import javax.inject.Inject;
import java.util.Collections;
import java.util.concurrent.Callable;

import static dev.nokee.commons.gradle.SpecUtils.named;
import static dev.nokee.commons.names.CppNames.nativeLinkConfigurationName;
import static dev.nokee.commons.names.CppNames.relocateMainForBinaryTaskName;
import static dev.nokee.companion.CppBinaryObjects.objectsOf;

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

	/**
	 * Returns a property to configure the tested binary of the C++ test executable.
	 * When selecting a different tested binary, i.e. testing against release binary, use this property!
	 * The testable objects are derived from this property, hence doesn't require a full rewire.
	 *
	 * @param testBinary  the test binary
	 * @return the tested binary property
	 */
	@SuppressWarnings("unchecked")
	public static Property<CppBinary> testedBinaryOf(CppTestExecutable testBinary) {
		return (Property<CppBinary>) ((ExtensionAware) testBinary).getExtensions().getByName("testedBinary");
	}

	@SuppressWarnings("UnstableApiUsage")
	static abstract /*final*/ class Rule implements Plugin<Project> {
		private final ObjectFactory objects;
		private final ProviderFactory providers;
		private final ZipProvider.Factory zipProviders;
		private final TaskContainer tasks;
		private final ConfigurationContainer configurations;
		private final DependencyFactory dependencyFactory;

		@Inject
		public Rule(ObjectFactory objects, ProviderFactory providers, TaskContainer tasks, ConfigurationContainer configurations) {
			this.objects = objects;
			this.providers = providers;
			this.zipProviders = objects.newInstance(ZipProvider.Factory.class);
			this.tasks = tasks;
			this.configurations = configurations;
			this.dependencyFactory = objects.newInstance(DependencyFactory.class);
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					final Property<ProductionCppComponent> testedComponent = objects.property(ProductionCppComponent.class);
					testedComponent.set(providers.provider(() -> {
						if (project.getPlugins().hasPlugin("cpp-application")) {
							return project.getExtensions().getByType(CppApplication.class);
						} else if (project.getPlugins().hasPlugin("cpp-library")) {
							return project.getExtensions().getByType(CppLibrary.class);
						} else {
							return null;
						}
					}));
					((ExtensionAware) testSuite).getExtensions().add("testedComponent", testedComponent);

					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
						final Property<CppBinary> testedBinary = objects.property(CppBinary.class);
						testedBinary.set(testedComponent.map(new TestedBinaryMapper(testExecutable) {
							@Override
							protected boolean isTestedBinary(CppTestExecutable testExecutable, ProductionCppComponent mainComponent, CppBinary testedBinary) {
								return testedBinary.getTargetMachine().getOperatingSystemFamily().getName().equals(testExecutable.getTargetMachine().getOperatingSystemFamily().getName())
									&& testedBinary.getTargetMachine().getArchitecture().getName().equals(testExecutable.getTargetMachine().getArchitecture().getName())
									&& !testedBinary.isOptimized()
									&& hasDevelopmentBinaryLinkage(mainComponent, testedBinary);
							}
						}));
						((ExtensionAware) testExecutable).getExtensions().add("testedBinary", testedBinary);

						// In cases where the task `relocateMainFor*` doesn't exist (for some reason),
						//   we can configure the task only when it appears (by name).
						tasks.withType(UnexportMainSymbol.class).configureEach(named(relocateMainForBinaryTaskName(testExecutable)::equals).whenSatisfied(task -> {
							task.getObjects().setFrom((Callable<?>) () -> objectsOf(testedBinaryOf(testExecutable).get()));
						}));

						// Recreate testable objects
						final ConfigurableFileCollection testableObjects = objects.fileCollection();
						testableObjects.from(zipProviders.zip(testedComponentOf(testSuite), testedBinaryOf(testExecutable), (mainComponent, mainBinary) -> {
							if (mainComponent instanceof CppApplication) {
								return (Callable<?>) () -> tasks.named(relocateMainForBinaryTaskName(testExecutable), UnexportMainSymbol.class)
									.map(UnexportMainSymbol::getRelocatedObjects);
							} else {
								return (Object) objectsOf(mainBinary);
							}
						}).orElse(Collections.emptyList()));

						// Assuming a single FileCollectionDependency which should be the Gradle core object files.
						//   In cases where this code executes **before** normal Gradle code (for some reason),
						//   we can remove the Gradle (previous) testableObjects dependency to replace it with our own.
						//   Note that we should normally be able to inspect the dependencies directly via:
						//     nativeLink.getDependencies().removeIf(it -> it instanceof FileCollectionDependency)
						//   Instead, we remove any current and future, FileCollectionDependency that doesn't match our testableObjects.
						final Configuration nativeLink = configurations.getByName(nativeLinkConfigurationName(testExecutable));
						nativeLink.getDependencies().all(dependencyCandidate -> {
							if (dependencyCandidate instanceof FileCollectionDependency) {
								if (!((FileCollectionDependency) dependencyCandidate).getFiles().equals(testableObjects)) {
									nativeLink.getDependencies().remove(dependencyCandidate);
								}
							}
						});
						nativeLink.getDependencies().add(dependencyFactory.create(testableObjects));
					});
				});
			});
		}
	}
}
