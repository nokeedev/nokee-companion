package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.backports.DependencyFactory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.attributes.Attributes;
import dev.nokee.commons.names.CppNames;
import dev.nokee.commons.names.Names;
import org.gradle.api.*;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.*;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.*;
import org.gradle.language.nativeplatform.tasks.UnexportMainSymbol;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryObjects.objectsOf;
import static dev.nokee.companion.CppBinaryProperties.*;
import static dev.nokee.companion.CppBinaryTaskExtensions.*;
import static dev.nokee.companion.CppSourceFiles.cppSourceOf;
import static dev.nokee.companion.NativeArtifactTypeDefinition.*;

/**
 * Represents missing properties that matches the tested component/binary for C++ test suites.
 */
public final class CppUnitTestExtensions {

	public static CppTestSuite testedComponent(CppTestSuite testSuite, Action<? super TestedComponentExtension> action) {
		((ExtensionAware) testSuite).getExtensions().configure("testedComponent", action);
		return testSuite;
	}

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
		private static final String TESTED_COMPONENT_EXTENSION_NAME = "testedComponent";

		private final ObjectFactory objects;
		private final ProviderFactory providers;
		private final TaskContainer tasks;
		private final ConfigurationContainer configurations;
		private final DependencyFactory dependencyFactory;
		private final ConfigurationRegistry configurationRegistry;

		@Inject
		public Rule(ObjectFactory objects, ProviderFactory providers, TaskContainer tasks, ConfigurationContainer configurations) {
			this.objects = objects;
			this.providers = providers;
			this.tasks = tasks;
			this.configurations = configurations;
			this.dependencyFactory = objects.newInstance(DependencyFactory.class);
			this.configurationRegistry = objects.newInstance(ConfigurationRegistry.class);
		}

		private static String libraryElementsOf(CppBinary binary) {
			if (binary instanceof CppStaticLibrary) {
				return LibraryElements.LINK_ARCHIVE;
			} else if (binary instanceof CppSharedLibrary) {
				return LibraryElements.DYNAMIC_LIB;
			} else {
				throw new UnsupportedOperationException();
			}
		}

		static abstract class UnpackObjectFiles implements TransformAction<TransformParameters.None> {
			@InputArtifact
			public abstract Provider<FileSystemLocation> getInputArtifact();

			@Inject
			public UnpackObjectFiles() {}

			@Override
			public void transform(TransformOutputs outputs) {
				System.out.println("TRANSFORM OBJECT_FILES");
				try {
					Files.walkFileTree(getInputArtifact().get().getAsFile().toPath(), new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							outputs.file(file);
							return FileVisitResult.CONTINUE;
						}
					});
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				System.out.println("BLAH");
			}
		}

//		static abstract class LibElemDisam implements AttributeDisambiguationRule<LibraryElements> {
//			@Inject
//			public LibElemDisam() {}
//
//			@Override
//			public void execute(MultipleCandidatesDetails<LibraryElements> details) {
//				Map<String, LibraryElements> vals = details.getCandidateValues().stream().collect(Collectors.toMap(Named::getName, it -> it));
//				vals.remove("objects");
//				LibraryElements result = vals.get(LibraryElements.DYNAMIC_LIB);
//				if (result == null) {
//					result = vals.get(LibraryElements.LINK_ARCHIVE);
//				}
//				details.closestMatch(result);
//			}
//		}
//
//		static abstract class LibElemCompat implements AttributeCompatibilityRule<LibraryElements> {
//			@Inject
//			public LibElemCompat() {}
//
//			@Override
//			public void execute(CompatibilityCheckDetails<LibraryElements> details) {
//				if (details.getConsumerValue().getName().equals("linkable-objects")) {
//					if (Arrays.asList(LibraryElements.DYNAMIC_LIB, LibraryElements.OBJECTS, LibraryElements.LINK_ARCHIVE).contains(details.getProducerValue().getName())) {
//						details.compatible();
//					}
//				}
//			}
//		}

//		static abstract class LibElemDisamEx implements AttributeDisambiguationRule<String> {
//			@Inject
//			public LibElemDisamEx() {}
//
//			@Override
//			public void execute(MultipleCandidatesDetails<String> details) {
//				System.out.println("DISAM " + details);
//				if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
//					if (details.getCandidateValues().contains("dev.nokee.linkable-objects")) {
//						details.closestMatch("dev.nokee.linkable-objects");
//					} else if (details.getCandidateValues().contains("com.apple.mach-o-dylib")) {
//						details.closestMatch("com.apple.mach-o-dylib");
//					}
//				}
//			}
//		}

//		static abstract class TestableEx implements AttributeDisambiguationRule<String> {
//			@Inject
//			public TestableEx() {}
//
//			@Override
//			public void execute(MultipleCandidatesDetails<String> details) {
//				System.out.println("DISAM " + details);
//				System.out.println("DISAM " + details.getConsumerValue());
//				System.out.println("DISAM " + details.getCandidateValues());
////				if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
////					if (details.getCandidateValues().contains("dev.nokee.linkable-objects")) {
////						details.closestMatch("dev.nokee.linkable-objects");
////					} else if (details.getCandidateValues().contains("com.apple.mach-o-dylib")) {
////						details.closestMatch("com.apple.mach-o-dylib");
////					}
////				}
//			}
//		}

		static abstract class LibElemCompatEx implements AttributeCompatibilityRule<String> {
			@Inject
			public LibElemCompatEx() {}

			@Override
			public void execute(CompatibilityCheckDetails<String> details) {
				System.out.println("COMAPT " + details.getProducerValue() + " -- " + details.getConsumerValue());
				if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
					if (Arrays.asList(LINKABLE_TYPES).contains(details.getProducerValue())) {
						details.compatible();
					}
				} else if (details.getConsumerValue().equals("dev.nokee.runnable-objects")) {
					if (Arrays.asList(RUNNABLE_TYPES).contains(details.getProducerValue())) {
						details.compatible();
					}
				} else if (details.getConsumerValue().equals(ArtifactTypeDefinition.DIRECTORY_TYPE)) {
					if (isDirectoryCompatibleType(details.getProducerValue())) {
						details.compatible();
					}
				}
			}
		}

		static abstract class TestableTypeCompat implements AttributeCompatibilityRule<String> {
			@Inject
			public TestableTypeCompat() {}

			@Override
			public void execute(CompatibilityCheckDetails<String> details) {
				System.out.println("??? COMAPT " + details.getProducerValue() + " -- " + details.getConsumerValue());
				if (Arrays.asList(details.getProducerValue().split("\\+")).contains(details.getConsumerValue())) {
					details.compatible();
				}
			}
		}

		@Override
		public void apply(Project project) {
			project.getPluginManager().apply(CppBinaryConfigurationRule.class);
			project.getPluginManager().apply(CppBinaryProperties.Rule.class);
			project.getPluginManager().apply(TestableElementsPlugin.class);
			project.getPluginManager().apply(NativeArtifactTypeDefinition.Rule.class);


			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE);

				project.getDependencies().registerTransform(UnpackObjectFiles.class, spec -> {
					spec.getFrom()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(OBJECT_CODE_TYPE))
						;
					spec.getTo()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
						;
				});

				project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getCompatibilityRules().add(LibElemCompatEx.class);


//				project.getDependencies().getAttributesSchema().attribute(Attribute.of("testable", String.class)).getDisambiguationRules().add(TestableEx.class);

//				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getCompatibilityRules().add(LibElemCompat.class);
//				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getDisambiguationRules().add(LibElemDisam.class);

				project.getDependencies().getAttributesSchema().attribute(Attribute.of("dev.nokee.testable-type", String.class)).getCompatibilityRules().add(TestableTypeCompat.class);

				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					TestedComponentExtension testedComponentExtension = ((ExtensionAware) testSuite).getExtensions().create(TESTED_COMPONENT_EXTENSION_NAME, TestedComponentExtension.class);
					testedComponentExtension.from(providers.provider(() -> {
						if (project.getPlugins().hasPlugin("cpp-application")) {
							return project.getExtensions().getByType(CppApplication.class);
						} else if (project.getPlugins().hasPlugin("cpp-library")) {
							return project.getExtensions().getByType(CppLibrary.class);
						} else {
							return null;
						}
					}));

					NamedDomainObjectProvider<Configuration> testedComponent = configurationRegistry.dependencyScope(CppNames.of(testSuite).configurationName("testedComponent"));
					testedComponent.configure(configuration -> configuration.setVisible(false));
					testedComponent.configure(configuration -> {
						final Provider<ModuleDependency> dependency = testedComponentExtension.testedComponent.map(it -> {
							ModuleDependency result = dependencyFactory.create(project);
							result.capabilities(capabilities -> {
//								if (!testedComponentExtension.testableTypeProvider.get().equals("library")) {
									capabilities.requireCapability(testedComponentExtension.testableTypeProvider.map(t -> "testable-type:" + t + ":1.0").get());
//								}
							});
//							result.attributes(attributes -> {
//								attributes.attributeProvider(Attribute.of("dev.nokee.testable-type", String.class), testedComponentExtension.testableTypeProvider);
////								attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, "testable+" + Category.LIBRARY));
////								attributes.attribute(Attribute.of("testable", String.class), "yes");
//							});
							return result;
						});
						configuration.getDependencies().addAllLater(objects.listProperty(Dependency.class).value(dependency.map(Collections::singletonList).orElse(Collections.emptyList())));
					});
					testSuite.getImplementationDependencies().extendsFrom(testedComponent.get());
//
					testSuite.getBinaries().configureEach(CppTestExecutable.class, testExecutable -> {
						NamedDomainObjectProvider<Configuration> testedSources = configurationRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("testedSources")));
						testedSources.configure(configuration -> {
							configuration.extendsFrom(testedComponent.get());
							configuration.attributes(attributes -> {
								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "native-compile"));
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(testExecutable)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(testExecutable)::get));
								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, testExecutable.getTargetMachine().getArchitecture());
								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, testExecutable.getTargetMachine().getOperatingSystemFamily());
//								attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, "testable+" + Category.LIBRARY));
//								attributes.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType.class, VerificationType.MAIN_SOURCES));
//								attributes.attribute(Attribute.of("testable", String.class), "yes");
							});
						});
						cppSourceOf(testExecutable).mut(objects.fileCollection().from((Callable<?>) () -> {
							return testedComponentExtension.testableTypeProvider.map(testableType -> {
								if (testableType.equals("sources")) {
									return testedSources.map(it -> {
										ArtifactView view = it.getIncoming().artifactView(v -> v.attributes(attributes -> {
											attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE));
										}));
										return view.getArtifacts().getArtifactFiles();
									}).get().getAsFileTree();
								}
								return Collections.emptyList();
							});
						})::plus);

						linkTask(testExecutable).configure(task -> {
							task.getLibs().setFrom((Callable<?>) () -> {
								ArtifactView view = configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().artifactView(it -> {
									it.attributes(attributes -> {
										attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
									});
								});
								return view.getArtifacts().getArtifactFiles();
							});
						});
						installTask(testExecutable).configure(task -> {
							task.setLibs(configurations.getByName(nativeRuntimeConfigurationName(testExecutable)).getIncoming().getArtifacts().getArtifactFiles());
						});
					});
				});

				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					// Detach compileIncludePath from testedComponent
					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
						final FileCollection includeDirs = objects.fileCollection().from((Callable<?>) () -> {
							ArtifactView view = configurations.getByName(cppCompileConfigurationName(testExecutable)).getIncoming().artifactView(viewConfiguration -> {
								viewConfiguration.attributes(attributeContainer -> {
									attributeContainer.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
								});
							});
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
//						// In cases where the task `relocateMainFor*` doesn't exist (for some reason),
//						//   we can configure the task only when it appears (by name).
//						tasks.withType(UnexportMainSymbol.class).configureEach(named(relocateMainForBinaryTaskName(testExecutable)::equals).using(Task::getName).whenSatisfied(task -> {
//							task.getObjects().setFrom(testableObjects);
//						}));
//
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

	public static class TestedComponentExtension {
		private final Property<ProductionCppComponent> testedComponent;
		private final Property<Object> testableType;
		private final Provider<String> testableTypeProvider;

		public TestedComponentExtension(ObjectFactory objects) {
			this.testedComponent = objects.property(ProductionCppComponent.class);
			this.testableType = objects.property(Object.class).convention("objects");

			this.testableTypeProvider = testedComponent.zip(testableType, (a,b) -> {
				if (a instanceof CppApplication && Arrays.asList("sources", "library").contains(b.toString())) {
					throw new UnsupportedOperationException(String.format("Cannot integrate as %s for application", b));
				}
				return b.toString();
			});
		}

		public TestedComponentExtension from(Provider<? extends ProductionCppComponent> testedComponent) {
			this.testedComponent.set(testedComponent);
			return this;
		}

		public TestedComponentExtension linkAgainst(Object type) {
			this.testableType.set(type);
			return this;
		}

		public String getObjects() {
			return "objects";
		}

		public String getProduct() {
			return "library";
		}

		public String getSources() {
			return "sources";
		}
	}

	/*private*/ static abstract /*final*/ class TestableElementsPlugin implements Plugin<Project> {
		private final ObjectFactory objects;
		private final ConfigurationRegistry configurationRegistry;
		private final ProviderFactory providers;
		private final ConfigurationContainer configurations;
		private final TaskContainer tasks;
		private final ProjectLayout layout;

		@Inject
		public TestableElementsPlugin(ObjectFactory objects, ProviderFactory providers, ConfigurationContainer configurations, TaskContainer tasks, ProjectLayout layout) {
			this.objects = objects;
			this.configurationRegistry = objects.newInstance(ConfigurationRegistry.class);
			this.providers = providers;
			this.configurations = configurations;
			this.tasks = tasks;
			this.layout = layout;
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
					component.getBinaries().configureEach(binary -> {
						final TestableElements testableElements = objects.newInstance(TestableElements.class, CppNames.of(binary).append("testable"), configurationRegistry);
						((ExtensionAware) binary).getExtensions().add("testable", testableElements);

						((ExtensionAware) binary).getExtensions().configure(TestableElements.class, configureAttributes(component, binary));
					});
				});
			});
		}

		private Action<TestableElements> configureAttributes(ProductionCppComponent component, CppBinary binary) {
			return new Action<TestableElements>() {
				@Override
				public void execute(TestableElements testable) {
					Attributes.Extension attributes = objects.newInstance(Attributes.Extension.class);

					testable.elements.all(it -> attributes.of(it, this::binaryAttributes));

//					testable.getSourceElements().configure(it -> it.setDescription("Testable source elements of " + binary + "."));
//					testable.getCppApiElements().configure(it -> it.setDescription("Testable API elements of " + binary + "."));
//					testable.getLinkElements().configure(it -> it.setDescription("Testable link elements of " + binary + "."));
//					testable.getRuntimeElements().configure(it -> it.setDescription("Testable runtime elements of " + binary + "."));

					if (component instanceof CppLibrary) {
						testable.elements.all(it -> attributes.of(it, details -> details.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.valueOf(CppNames.qualifyingName(binary).get("linkageName").toString().toUpperCase(Locale.ENGLISH)))));
					}

//
					testable.getCppApiElements().configure(it -> {
						if (component instanceof CppApplication) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
//							System.out.println("EXTENDS FROM " + configurations.getByName(cppApiElementsConfigurationName(component)).getExtendsFrom());
							it.extendsFrom(configurations.getByName(cppApiElementsConfigurationName(component)).getExtendsFrom().toArray(new Configuration[0]));
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						}
					});
					testable.getLinkElements().configure(it -> {
						if (binary instanceof CppExecutable) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
							it.extendsFrom(configurations.getByName(linkElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						}
					});
					testable.productLinkElements.configure(it -> {
						if (binary instanceof CppExecutable) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
							it.extendsFrom(configurations.getByName(linkElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						}
					});
					testable.sourceLinkElements.configure(it -> {
						if (binary instanceof CppExecutable) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
							it.extendsFrom(configurations.getByName(linkElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						}
					});
					testable.getRuntimeElements().configure(it -> it.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0])));
					testable.productRuntimeElements.configure(it -> it.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0])));
//
					testable.getSourceElements().configure(outgoing(it -> {
//						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
//							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
//						}));
						it.capability("testable-type:sources:1.0");

						if (component instanceof CppLibrary) {
//							it.getVariants().create("sources", variant -> {
								it.artifact(tasks.register(CppNames.of(binary).taskName("sync", "sources").toString(), Sync.class, task -> {
									task.from(CppSourceFiles.cppSourceOf(binary));
									task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
								}), spec -> spec.setType(directoryType(C_PLUS_PLUS_SOURCE_TYPE)));
//							});
						}
//						it.getVariants().create("objects", variant -> { /* nothing */ });
//						if (!(component instanceof CppApplication)) {
//							it.getVariants().create("library", variant -> { /* nothing */ }); // TODO: SHOULD NOT EXISTS ON APP
//						}
					}));
					testable.getCppApiElements().configure(outgoing(it -> {
						TaskProvider<Sync> allHeadersTask = tasks.register(CppNames.of(binary).taskName("sync", "cppHeaders").toString(), Sync.class, task -> {
							task.from(component.getHeaderFiles());
							task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
						});
						it.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_HEADER_TYPE)));
					}));
					testable.getLinkElements().configure(outgoing(it -> {
//						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
//							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
//						}));
//						it.getVariants().create("sources", variant -> { /* nothing */ });
//						it.getVariants().create("objects", variant -> {
//							attributes.of(variant, details -> {
//								details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.OBJECTS);
//							});
							if (component instanceof CppLibrary) {
								it.artifact(tasks.register(CppNames.of(binary).taskName("sync", "objects").toString(), Sync.class, task -> {
									task.from(objectsOf(binary));
									task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
								}), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
							} else if (component instanceof CppApplication) {
								it.artifact(tasks.register(CppNames.of(binary).taskName("relocateMain").toString(), UnexportMainSymbol.class, task -> {
									task.getObjects().from(objectsOf(binary));
									task.getOutputDirectory().set(layout.getBuildDirectory().dir("tmp/" + task.getName()));
								}), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
							}
//						});
//						if (component instanceof CppLibrary) {
//							it.getVariants().create("library", variant -> {
//								variant.getArtifacts().addAllLater(configurations.named(linkElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
//							});
//						}
					}));

					testable.productLinkElements.configure(outgoing(it -> {
						it.capability("testable-type:library:1.0");
						if (component instanceof CppLibrary) {
							it.getArtifacts().addAllLater(configurations.named(linkElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
						}
//						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
//							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
//						}));
//						it.getVariants().create("sources", variant -> { /* nothing */ });
//						it.getVariants().create("objects", variant -> {
//							attributes.of(variant, details -> {
//								details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.OBJECTS);
//							});
//						if (component instanceof CppLibrary) {
//							it.artifact(tasks.register(CppNames.of(binary).taskName("sync", "objects").toString(), Sync.class, task -> {
//								task.from(objectsOf(binary));
//								task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
//							}), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
//						} else if (component instanceof CppApplication) {
//							it.artifact(tasks.register(CppNames.of(binary).taskName("relocateMain").toString(), UnexportMainSymbol.class, task -> {
//								task.getObjects().from(objectsOf(binary));
//								task.getOutputDirectory().set(layout.getBuildDirectory().dir("tmp/" + task.getName()));
//							}), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
//						}
//						});
//						if (component instanceof CppLibrary) {
//							it.getVariants().create("library", variant -> {
//								variant.getArtifacts().addAllLater(configurations.named(linkElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
//							});
//						}
					}));


//					testable.getRuntimeElements().configure(outgoing(it -> {
//						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
//							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
//						}));
//						it.getVariants().create("sources", variant -> { /* nothing */ });
//						it.getVariants().create("objects", variant -> { /* nothing */ });
//						it.getVariants().create("library", variant -> {
//							variant.getArtifacts().addAllLater(configurations.named(runtimeElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
//						});
//					}));

					testable.productLinkElements.configure(outgoing(it -> {
						it.capability("testable-type:library:1.0");
						it.getArtifacts().addAllLater(configurations.named(runtimeElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
					}));
				}

				private /*static*/ Action<Configuration> outgoing(Action<ConfigurationPublications> action) {
					return it -> it.outgoing(action);
				}

				private void binaryAttributes(Attributes.Details details) {
					details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(debuggabilityOf(binary)::get));
					details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(optimizationOf(binary)::get));
					details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(binary.getTargetMachine().getArchitecture());
					details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(binary.getTargetMachine().getOperatingSystemFamily());
					details.attribute(Attribute.of("testable", String.class)).of("yes");
				}
			};
		}
	}

	/*private*/ static abstract /*final*/ class TestableElements {
		private final NamedDomainObjectProvider<Configuration> sourceElements;
		private final NamedDomainObjectProvider<Configuration> cppApiElements;
		private final NamedDomainObjectProvider<Configuration> linkElements;
		private final NamedDomainObjectProvider<Configuration> sourceLinkElements;
		private final NamedDomainObjectProvider<Configuration> productLinkElements;
		private final NamedDomainObjectProvider<Configuration> runtimeElements;
		private final NamedDomainObjectProvider<Configuration> productRuntimeElements;
		private final NamedDomainObjectSet<Configuration> elements;

		@Inject
		public TestableElements(Names names, ConfigurationRegistry configurations, ObjectFactory objects, ProviderFactory providers) {
			this.sourceElements = configurations.consumable(names.configurationName("sourceElements"));
			this.cppApiElements = configurations.consumable(names.configurationName("cppApiElements"));
			this.linkElements = configurations.consumable(names.configurationName("linkElements"));
			this.sourceLinkElements = configurations.consumable(names.configurationName("sourceLinkElements"));
			this.productLinkElements = configurations.consumable(names.configurationName("productLinkElements"));
			this.runtimeElements = configurations.consumable(names.configurationName("runtimeElements"));
			this.productRuntimeElements = configurations.consumable(names.configurationName("productRuntimeElements"));

			this.elements = objects.namedDomainObjectSet(Configuration.class);
			elements.add(sourceElements.get());
			elements.add(cppApiElements.get());
			elements.add(linkElements.get());
			elements.add(sourceLinkElements.get());
			elements.add(productLinkElements.get());
			elements.add(runtimeElements.get());
			elements.add(productRuntimeElements.get());

			elements.all(it -> it.setVisible(false));

			Attributes.Extension attributes = objects.newInstance(Attributes.Extension.class);
			sourceElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of("native-compile");
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
				details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of("sources-cplusplus");
//				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.VERIFICATION);
//				details.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE).of(VerificationType.MAIN_SOURCES);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), providers.provider(() -> testableType(c.getOutgoing())));
			}));
			cppApiElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:library:1.0");
					outgoing.capability("testable-type:objects:1.0");
					outgoing.capability("testable-type:sources:1.0");
				});
			});
			cppApiElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.C_PLUS_PLUS_API);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
				details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.HEADERS_CPLUSPLUS);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), "sources+objects");
			}));
			linkElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:objects:1.0");
				});
			});
			linkElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_LINK);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), providers.provider(() -> testableType(c.getOutgoing())));
			}));

			productLinkElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:library:1.0");
				});
			});
			productLinkElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_LINK);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
			}));

			sourceLinkElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:sources:1.0");
				});
			});
			sourceLinkElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_LINK);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), providers.provider(() -> testableType(c.getOutgoing())));
			}));

			// TODO: ONLY FOR LIBRARY
			runtimeElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:objects:1.0");
					outgoing.capability("testable-type:sources:1.0");
				});
			});
			runtimeElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_RUNTIME);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), providers.provider(() -> testableType(c.getOutgoing())));
				/* nothing to export for both sources and objects */
			}));

			productRuntimeElements.configure(c -> {
				c.outgoing(outgoing -> {
					outgoing.capability("testable-type:library:1.0");
				});
			});
			productRuntimeElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_RUNTIME);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class), providers.provider(() -> testableType(c.getOutgoing())));
				/* nothing to export for both sources and objects */
			}));
		}

		private String testableType(ConfigurationPublications outgoing) {
			return outgoing.getVariants().stream().map(t -> t.getAttributes().getAttribute(Attribute.of("dev.nokee.testable-type", String.class))).collect(Collectors.joining("+"));
		}

		public NamedDomainObjectProvider<Configuration> getSourceElements() {
//			throw new UnsupportedOperationException();
			return sourceElements;
		}

		public NamedDomainObjectProvider<Configuration> getCppApiElements() {
			return cppApiElements;
		}

		public NamedDomainObjectProvider<Configuration> getLinkElements() {
			return linkElements;
		}

		public NamedDomainObjectProvider<Configuration> getRuntimeElements() {
			return runtimeElements;
		}
	}
}
