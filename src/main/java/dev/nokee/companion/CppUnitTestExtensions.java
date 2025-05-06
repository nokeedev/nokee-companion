package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.backports.DependencyFactory;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.attributes.Attributes;
import dev.nokee.commons.names.CppNames;
import dev.nokee.commons.names.Names;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
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
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithLinkFile;
import org.gradle.language.nativeplatform.ComponentWithRuntimeFile;
import org.gradle.language.nativeplatform.*;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryObjects.objectsOf;
import static dev.nokee.companion.CppBinaryProperties.*;
import static dev.nokee.companion.CppBinaryTaskExtensions.compileTask;
import static dev.nokee.companion.CppSourceFiles.cppSourceOf;

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
		private static final String TESTABLE_OBJECTS_PROPERTY_NAME = "testableObjects";
		private static final String TESTED_BINARY_EXTENSION_NAME = "testedBinary";
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

		//region For displayName on Provider instance
		/*private*/ interface TestedComponentPropertyProvider {
			Property<ProductionCppComponent> getTestedComponent();
		}

		/*private*/ interface TestedBinaryPropertyProvider {
			Property<CppBinary> getTestedBinary();
		}
		//endregion

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
			}
		}

		static abstract class LibElemDisam implements AttributeDisambiguationRule<LibraryElements> {
			@Inject
			public LibElemDisam() {}

			@Override
			public void execute(MultipleCandidatesDetails<LibraryElements> details) {
				Map<String, LibraryElements> vals = details.getCandidateValues().stream().collect(Collectors.toMap(it -> it.getName(), it -> it));
				vals.remove("objects");
				LibraryElements result = vals.get(LibraryElements.DYNAMIC_LIB);
				if (result == null) {
					result = vals.get(LibraryElements.LINK_ARCHIVE);
				}
				details.closestMatch(result);
			}
		}

		static abstract class LibElemCompat implements AttributeCompatibilityRule<LibraryElements> {
			@Inject
			public LibElemCompat() {}

			@Override
			public void execute(CompatibilityCheckDetails<LibraryElements> details) {
				if (details.getConsumerValue().getName().equals("linkable-objects")) {
					if (Arrays.asList(LibraryElements.DYNAMIC_LIB, LibraryElements.OBJECTS, LibraryElements.LINK_ARCHIVE).contains(details.getProducerValue().getName())) {
						details.compatible();
					}
				}
			}
		}

		@Override
		public void apply(Project project) {
			project.getPluginManager().apply(CppBinaryConfigurationRule.class);
			project.getPluginManager().apply(CppBinaryProperties.Rule.class);
			project.getPluginManager().apply(TestableElementsPlugin.class);


			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE);

				project.getDependencies().registerTransform(UnpackObjectFiles.class, spec -> {
					spec.getFrom()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "objects-directory")
						;
					spec.getTo()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "linkable-objects")
						;
				});

				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getCompatibilityRules().add(LibElemCompat.class);
				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).getDisambiguationRules().add(LibElemDisam.class);

				// Rewire compileIncludePath
				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
						ArtifactView includeDirs = configurations.getByName(cppCompileConfigurationName(testExecutable)).getIncoming().artifactView(viewConfiguration -> {
							viewConfiguration.attributes(attributeContainer -> {
								attributeContainer.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
							});
						});
						ShadowProperty<FileCollection> compileIncludePath = compileIncludePathOf(testExecutable);
						compileIncludePath.set(testSuite.getPrivateHeaderDirs().plus(includeDirs.getFiles()));
						compileTask(testExecutable).configure(task -> {
							task.getIncludes().setFrom(compileIncludePath);
							task.doFirst(__ -> {
								for (ResolvedArtifactResult artifact : includeDirs.getArtifacts().getArtifacts()) {
									System.out.println("=====> " + artifact);
								}
							});
						});
					});
				});

//				project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
//					component.getBinaries().configureEach(binary -> {
////						// objects
////
////						// TODO: For application, create a linkElements configuration with visibility set to false to expose the objects
//						configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
//							// TODO: attach objects as variant
//							configuration.outgoing(outgoing -> {
//								outgoing.attributes(attributes -> {
//									attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, libraryElementsOf(binary)));
//								});
//								outgoing.variants(variants -> {
//									variants.create("objects", variant -> {
//										variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "objects").toString(), Sync.class, task -> {
//											task.from(objectsOf(binary));
//											task.setDestinationDir(project.file(project.getLayout().getBuildDirectory().dir("generated-objs/main/cpp")));
//										}), it -> it.setType("objects-directory"));
//										variant.attributes(attributes -> {
//											attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS));
//										});
//										// TODO: Transforms directory into object-files
//									});
//									variants.create("sources", variant -> {
//										variant.attributes(attributes -> {
//											attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "cpp-sources"));
//										});
//									});
//								});
//							});
//						});
//						configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
//							// TODO: attach objects as variant
//							configuration.outgoing(outgoing -> {
//								outgoing.attributes(attributes -> {
//									attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, libraryElementsOf(binary)));
//								});
//								outgoing.variants(variants -> {
//									variants.create("objects", variant -> {
//										variant.attributes(attributes -> {
//											attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS));
//										});
//									});
//									variants.create("sources", variant -> {
//										variant.attributes(attributes -> {
//											attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "cpp-sources"));
//										});
//									});
//								});
//							});
//						});
//						configurationRegistry.consumable(CppNames.of(binary).configurationName("sourceElements")).configure(configuration -> {
//							configuration.extendsFrom(configurations.getByName(CppNames.of(binary).implementationConfigurationName().toString()));
//							configuration.attributes(attributes -> {
//								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "cplusplus-testable-sources"));
//								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(new ShadowProperty<>(binary, "optimized", binary::isOptimized)::get));
//								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(new ShadowProperty<>(binary, "debuggable", binary::isDebuggable)::get));
//								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
//								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());
////								attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "cpp-sources"));
//
////								attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, "external"));
////								attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, "verification"));
////								attributes.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType.class, "main-sources"));
//							});
//							configuration.outgoing(outgoing -> {
//								outgoing.getVariants().create("source", variant -> {
//									variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "sourceElements").toString(), Sync.class, task -> {
//										task.from(CppSourceFiles.cppSourceOf(binary));
//										task.setDestinationDir(project.file(project.getLayout().getBuildDirectory().dir("generated-src/main/cpp")));
//									}));
//									variant.attributes(attributes -> {
//										attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "cpp-sources"));
//									});
//								});
//								outgoing.getVariants().create("objects", variant -> {
//									variant.attributes(attributes -> {
//										attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS));
//									});
//								});
//								outgoing.getVariants().create("link", variant -> {
//									variant.attributes(attributes -> {
//										attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "linkable-objects"));
//									});
//								});
//							});
//						});
//					});
//				});

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
					testedComponent.configure(configuration -> {
						final Provider<ModuleDependency> dependency = testedComponentExtension.testedComponent.map(it -> dependencyFactory.create(project).attributes(attributes -> {
							attributes.attributeProvider(Attribute.of("dev.nokee.testable-type", String.class), testedComponentExtension.testableType);
						}));
						configuration.getDependencies().addAllLater(objects.listProperty(Dependency.class).value(dependency.map(Collections::singletonList).orElse(Collections.emptyList())));
					});

					testSuite.getImplementationDependencies().extendsFrom(testedComponent.get());

//					NamedDomainObjectProvider<Configuration> testedSources = configurationRegistry.resolvable(CppNames.of(testSuite).configurationName(it -> it.prefix("testedSources")));
//					testedSources.configure(configuration -> {
//						configuration.extendsFrom(testedComponent.get());
//						configuration.attributes(attributes -> {
//							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "native-library"));
//						});
//					});
//					cppSourceOf(testSuite).mut(testedSources.get()::plus);

					testSuite.getBinaries().configureEach(testExecutable -> {
						NamedDomainObjectProvider<Configuration> testedSources = configurationRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("testedSources")));
						testedSources.configure(configuration -> {
							configuration.extendsFrom(testedComponent.get());
							configuration.attributes(attributes -> {
								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "cplusplus-sources"));
//								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "cplusplus-sources"));
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(testExecutable)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(testExecutable)::get));
								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, testExecutable.getTargetMachine().getArchitecture());
								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, testExecutable.getTargetMachine().getOperatingSystemFamily());
								attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.VERIFICATION));
								attributes.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType.class, VerificationType.MAIN_SOURCES));
//								attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "linkable-objects"));
							});
						});
						cppSourceOf(testExecutable).mut(testedSources.map(it -> {
							return it.getIncoming().artifactView(v -> v.attributes(attributes -> {
								attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "cpp-source-directory");
							})).getArtifacts().getArtifactFiles();
						}).get().getAsFileTree()::plus);

						ShadowProperty<FileCollection> compileIncludePath = compileIncludePathOf(testExecutable);
						compileIncludePath.mut(objects.fileCollection().from((Callable<?>) testedSources.map(it -> {
							return it.getIncoming().artifactView(v -> v.attributes(attributes -> {
								attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
							})).getArtifacts().getArtifactFiles();
						})::get)::plus);
//						compileTask(testExecutable).configure(task -> {
//							task.doFirst(__ -> {
//								for (File include : task.getIncludes()) {
//									System.out.println("--> " + include);
//								}
//
//								for (File file : testedSources.map(it -> {
//									return it.getIncoming().artifactView(v -> v.attributes(attributes -> {
//										attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
//									})).getArtifacts().getArtifactFiles();
//								}).get().getFiles()) {
//									System.out.println(file);
//								}
//							});
//							task.includes(testedSources.map(it -> {
//								return it.getIncoming().artifactView(v -> v.attributes(attributes -> {
//									attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
//								})).getArtifacts().getArtifactFiles();
//							}).get());
//						});
//
//
//
//						NamedDomainObjectProvider<Configuration> testedObjects = configurationRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("testedObjects")));
//						testedObjects.configure(configuration -> {
//							configuration.extendsFrom(testedComponent.get());
//							configuration.attributes(attributes -> {
//								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(ShadowProperty.of(testExecutable, "optimized", testExecutable::isOptimized)::get));
//								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(ShadowProperty.of(testExecutable, "debuggable", testExecutable::isDebuggable)::get));
//								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, testExecutable.getTargetMachine().getArchitecture());
//								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, testExecutable.getTargetMachine().getOperatingSystemFamily());
//								attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "native-library"));
//							});
//						});
//						objectsOf(testExecutable).mut(testedObjects.map(it -> it.getIncoming().artifactView(t -> t.attributes(attributes -> {
//							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "native-library"));
//						})).getArtifacts().getArtifactFiles()).get()::plus);
						configurations.named(nativeLinkConfigurationName(testExecutable)).configure(configuration -> {
							configuration.attributes(attributes -> {
								attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "linkable-objects"));
							});
						});

						tasks.named(linkTaskName(testExecutable), AbstractLinkTask.class).configure(task -> {
							task.doFirst(__ -> {
								for (File file : configurations.getByName(nativeLinkConfigurationName(testExecutable))) {
									System.out.println("-=-=> " + file);
								}
//								for (File file : configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().artifactView(it -> {
//									it.attributes(attributes -> {
//										attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "linkable-objects");
//									});
//								}).getFiles()) {
//									System.out.println("FOO " + file);
//								}
							});
//							task.getLibs().from(libs);
							task.getLibs().setFrom(configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().getArtifacts().getResolvedArtifacts().map(it -> {
								List<Object> result = new ArrayList<>();
								for (ResolvedArtifactResult t : it) {
//									System.out.println(t);
//									System.out.println(objects.newInstance(Attributes.Extension.class).of(t.getVariant()).getAsMap().get());
//									System.out.println(t.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE));
									if (t.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).equals("objects-directory")) {
										result.add(objects.fileCollection().from(t.getFile()).getAsFileTree());
									} else {
										result.add(t.getFile());
									}
								}
								return result;
							}));
						});
					});
				});

				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
//					final Property<ProductionCppComponent> testedComponent = objects.newInstance(TestedComponentPropertyProvider.class).getTestedComponent();
//					testedComponent.set(providers.provider(() -> {
//						if (project.getPlugins().hasPlugin("cpp-application")) {
//							return project.getExtensions().getByType(CppApplication.class);
//						} else if (project.getPlugins().hasPlugin("cpp-library")) {
//							return project.getExtensions().getByType(CppLibrary.class);
//						} else {
//							return null;
//						}
//					}));
//					((ExtensionAware) testSuite).getExtensions().add(TESTED_COMPONENT_EXTENSION_NAME, testedComponent);

//					((DefaultCppTestSuite) testSuite).getTestedComponent().set((CppComponent) null);

					// Detach implementation configuration from testedComponent
					testSuite.getBinaries().whenElementFinalized(CppTestExecutable.class, testExecutable -> {
						configurations.named(implementationConfigurationName(testExecutable)).configure(it -> {
							it.setExtendsFrom(it.getExtendsFrom().stream().filter(t -> !(t.getName().startsWith("main") && t.getName().endsWith("Implementation"))).collect(Collectors.toList()));
						});
					});

					testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
//						final Property<CppBinary> testedBinary = objects.newInstance(TestedBinaryPropertyProvider.class).getTestedBinary();
//						testedBinary.set(testedComponent.map(new TestedBinaryMapper(testExecutable) {
//							@Override
//							protected boolean isTestedBinary(CppTestExecutable testExecutable, ProductionCppComponent mainComponent, CppBinary testedBinary) {
//								return testedBinary.getTargetMachine().getOperatingSystemFamily().getName().equals(testExecutable.getTargetMachine().getOperatingSystemFamily().getName())
//									&& testedBinary.getTargetMachine().getArchitecture().getName().equals(testExecutable.getTargetMachine().getArchitecture().getName())
//									&& !testedBinary.isOptimized()
//									&& hasDevelopmentBinaryLinkage(mainComponent, testedBinary);
//							}
//						}));
//						((ExtensionAware) testExecutable).getExtensions().add(TESTED_BINARY_EXTENSION_NAME, testedBinary);
//
//						// To allow reassignment, we must use extra properties
//						((ExtensionAware) testExecutable).getExtensions().getExtraProperties().set(TESTABLE_OBJECTS_PROPERTY_NAME, objects.fileCollection().from((Callable<?>) () -> objectsOf(testedBinaryOf(testExecutable).get())));
//
//						// Recreate testable objects
//						final ConfigurableFileCollection testableObjects = objects.fileCollection();
//						testableObjects.from((Callable<?>) () -> {
//							final ExtraPropertiesExtension testExecutableExts = ((ExtensionAware) testExecutable).getExtensions().getExtraProperties();
//							if (testExecutableExts.has(TESTABLE_OBJECTS_PROPERTY_NAME)) {
//								return testExecutableExts.get(TESTABLE_OBJECTS_PROPERTY_NAME);
//							}
//							return Collections.emptyList();
//						});
//
//						// In cases where the task `relocateMainFor*` doesn't exist (for some reason),
//						//   we can configure the task only when it appears (by name).
//						tasks.withType(UnexportMainSymbol.class).configureEach(named(relocateMainForBinaryTaskName(testExecutable)::equals).using(Task::getName).whenSatisfied(task -> {
//							task.getObjects().setFrom(testableObjects);
//						}));
//
//						// As we cannot use addAllLater because we are using `.all` hook to remove the "core testable objects",
//						//   we use an FileCollection indirection that will either return the testableObjects or an empty list.
//						final ConfigurableFileCollection actualTestableObjects = objects.fileCollection().from(testedComponentOf(testSuite).map(mainComponent -> {
//							if (mainComponent instanceof CppApplication) {
//								return tasks.named(relocateMainForBinaryTaskName(testExecutable), UnexportMainSymbol.class)
//									.map(UnexportMainSymbol::getRelocatedObjects);
//							} else {
//								return testableObjects;
//							}
//						}).orElse(Collections.emptyList()));

						// Detach testedObjects from nativeLinkTest
						// Assuming a single FileCollectionDependency which should be the Gradle core object files.
						//   In cases where this code executes **before** normal Gradle code (for some reason),
						//   we can remove the Gradle (previous) testableObjects dependency to replace it with our own.
						//   Note that we should normally be able to inspect the dependencies directly via:
						//     nativeLink.getDependencies().removeIf(it -> it instanceof FileCollectionDependency)
						//   Instead, we remove any current and future, FileCollectionDependency that doesn't match our testableObjects.
						final Configuration nativeLink = configurations.getByName(nativeLinkConfigurationName(testExecutable));
						nativeLink.getDependencies().all(dependencyCandidate -> {
							if (dependencyCandidate instanceof FileCollectionDependency) {
								nativeLink.getDependencies().remove(dependencyCandidate);
							}
						});

//						// There may-or-may-not have "testable objects" for this test suite
//						final NamedDomainObjectProvider<Configuration> componentUnderTest = configurationRegistry.dependencyScope(CppNames.of(testExecutable).configurationName("componentUnderTest").toString());
//						componentUnderTest.configure(it -> it.getDependencies().addAllLater(objects.listProperty(Dependency.class).value(Collections.singletonList(dependencyFactory.create(actualTestableObjects))).orElse(Collections.emptyList())));
//						nativeLink.extendsFrom(componentUnderTest.get());
					});
				});
			});
		}
	}

	public static class TestedComponentExtension {
		private final Property<ProductionCppComponent> testedComponent;
		private final Property<String> testableType;

		public TestedComponentExtension(ObjectFactory objects) {
			this.testedComponent = objects.property(ProductionCppComponent.class);
			this.testableType = objects.property(String.class).convention("objects");
		}

		public TestedComponentExtension from(Provider<? extends ProductionCppComponent> testedComponent) {
			this.testedComponent.set(testedComponent);
			return this;
		}

		public TestedComponentExtension linkAgainst(String type) {
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
			project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
				component.getBinaries().configureEach(binary -> {
					final TestableElements testableElements = objects.newInstance(TestableElements.class, CppNames.of(binary).append("testable"), configurationRegistry);
					((ExtensionAware) binary).getExtensions().add("testable", testableElements);

					((ExtensionAware) binary).getExtensions().configure(TestableElements.class, configureAttributes(component, binary));
				});
			});
		}

		private Action<TestableElements> configureAttributes(ProductionCppComponent component, CppBinary binary) {
			return new Action<TestableElements>() {
				@Override
				public void execute(TestableElements testable) {
					Attributes.Extension attributes = objects.newInstance(Attributes.Extension.class);

					testable.getSourceElements().configure(it -> attributes.of(it, this::binaryAttributes));
//					testable.getCppApiElements().configure(it -> attributes.of(it, this::binaryAttributes));
					testable.getLinkElements().configure(it -> attributes.of(it, this::binaryAttributes));
					testable.getRuntimeElements().configure(it -> attributes.of(it, this::binaryAttributes));

					testable.getSourceElements().configure(it -> it.setDescription("Testable source elements of " + binary + "."));
//					testable.getCppApiElements().configure(it -> it.setDescription("Testable API elements of " + binary + "."));
					testable.getLinkElements().configure(it -> it.setDescription("Testable link elements of " + binary + "."));
					testable.getRuntimeElements().configure(it -> it.setDescription("Testable runtime elements of " + binary + "."));

//					testable.getCppApiElements().configure(it -> it.extendsFrom(configurations.getByName(cppApiElementsConfigurationName(component)).getExtendsFrom().toArray(new Configuration[0])));
					testable.getLinkElements().configure(it -> {
						if (binary instanceof CppExecutable) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
							it.extendsFrom(configurations.getByName(linkElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						}
					});
					testable.getRuntimeElements().configure(it -> it.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0])));

					testable.getSourceElements().configure(it -> attributes.of(it, details -> {
						details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(ShadowProperty.of(binary, "optimized", binary::isOptimized)::get));
						details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(ShadowProperty.of(binary, "debuggable", binary::isDebuggable)::get));
						details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(binary.getTargetMachine().getArchitecture());
						details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(binary.getTargetMachine().getOperatingSystemFamily());
					}));
					testable.getSourceElements().configure(outgoing(it -> {
						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
						}));

						TaskProvider<Sync> privateHeadersTask = tasks.register(CppNames.of(binary).taskName("sync", "privateHeaders").toString(), Sync.class, task -> {
							FileCollection privateHeaders = component.getHeaderFiles();
							if (component instanceof CppLibrary) {
								privateHeaders = privateHeaders.minus(((CppLibrary) component).getPublicHeaderFiles());
							}
							task.from(privateHeaders);
							task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
						});

						it.getVariants().create("sources", variant -> {
							variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "sources").toString(), Sync.class, task -> {
								task.from(CppSourceFiles.cppSourceOf(binary));
								task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
							}), spec -> spec.setType("cpp-source-directory"));
						});
						it.getVariants().create("private-sources", variant -> {
							attributes.of(variant, details -> {
								details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of("sources");
							});
							variant.artifact(privateHeadersTask, spec -> spec.setType(ArtifactTypeDefinition.DIRECTORY_TYPE));
						});
						it.getVariants().create("objects", variant -> { /* nothing */ });
						it.getVariants().create("library", variant -> { /* nothing */ }); // TODO: SHOULD NOT EXISTS ON APP
					}));
//					testable.getCppApiElements().configure(outgoing(it -> {
//						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
//							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
//						}));
//
//						if (component instanceof CppLibrary) {
//							TaskProvider<Sync> syncPublicHeaders = tasks.register(CppNames.of(binary).taskName("sync", "publicHeaders").toString(), Sync.class, task -> {
//								task.from(((CppLibrary) component).getPublicHeaderFiles());
//								task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
//							});
//							it.getVariants().configureEach(variant -> variant.artifact(syncPublicHeaders, spec -> spec.setType(ArtifactTypeDefinition.DIRECTORY_TYPE)));
//						}
//
//						it.getVariants().create("sources", variant -> {
//							variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "privateHeaders").toString(), Sync.class, task -> {
//								FileCollection privateHeaders = component.getHeaderFiles();
//								if (component instanceof CppLibrary) {
//									privateHeaders = privateHeaders.minus(((CppLibrary) component).getPublicHeaderFiles());
//								}
//								task.from(privateHeaders);
//								task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
//							}), spec -> spec.setType(ArtifactTypeDefinition.DIRECTORY_TYPE));
//						});
//						it.getVariants().create("objects", variant -> {});
//						it.getVariants().create("library", variant -> {}); // TODO: SHOULD NOT EXISTS ON APP
//					}));
					testable.getLinkElements().configure(it -> attributes.of(it, details -> {
						details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of("linkable-objects");
					}));
					testable.getLinkElements().configure(outgoing(it -> {
						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
						}));
						it.getVariants().create("sources", variant -> { /* nothing */ });
						it.getVariants().create("objects", variant -> {
							attributes.of(variant, details -> {
								details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.OBJECTS);
							});
							// TODO: RElocate objects for C++ app
							variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "objects").toString(), Sync.class, task -> {
								task.from(objectsOf(binary));
								task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
							}), spec -> spec.setType("objects-directory"));
						});
							it.getVariants().create("library", variant -> {
								attributes.of(variant, details -> {
									details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.DYNAMIC_LIB);
								});
						if (component instanceof CppLibrary) {
								variant.artifact(((ComponentWithLinkFile) binary).getLinkFile());  // TODO: SHOULD NOT EXISTS ON APP
						}
							});
					}));
					testable.getRuntimeElements().configure(outgoing(it -> {
						it.getVariants().configureEach(variant -> attributes.of(variant, details -> {
							details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of(variant.getName());
						}));
						it.getVariants().create("sources", variant -> { /* nothing */ });
						it.getVariants().create("objects", variant -> { /* nothing */ });
						it.getVariants().create("library", variant -> {
							if (binary instanceof CppSharedLibrary) {
								variant.artifact(((CppSharedLibrary) binary).getRuntimeFile());
							}
						});
					}));
				}

				private /*static*/ Action<Configuration> outgoing(Action<ConfigurationPublications> action) {
					return it -> it.outgoing(action);
				}

				private void binaryAttributes(Attributes.Details details) {
					details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(ShadowProperty.of(binary, "debuggable", binary::isDebuggable)::get));
					details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(ShadowProperty.of(binary, "optimized", binary::isOptimized)::get));
					details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(binary.getTargetMachine().getArchitecture());
					details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(binary.getTargetMachine().getOperatingSystemFamily());
				}
			};
		}
	}

	/*private*/ static abstract /*final*/ class TestableElements {
		private final NamedDomainObjectProvider<Configuration> sourceElements;
//		private final NamedDomainObjectProvider<Configuration> cppApiElements;
		private final NamedDomainObjectProvider<Configuration> linkElements;
		private final NamedDomainObjectProvider<Configuration> runtimeElements;

		@Inject
		public TestableElements(Names names, ConfigurationRegistry configurations, ObjectFactory objects, ProviderFactory providers) {
			this.sourceElements = configurations.consumable(names.configurationName("sourceElements"));
//			this.cppApiElements = configurations.consumable(names.configurationName("cppApiElements"));
			this.linkElements = configurations.consumable(names.configurationName("linkElements"));
			this.runtimeElements = configurations.consumable(names.configurationName("runtimeElements"));

			sourceElements.configure(it -> it.setVisible(false));
//			cppApiElements.configure(it -> it.setVisible(false));
			linkElements.configure(it -> it.setVisible(false));
			runtimeElements.configure(it -> it.setVisible(false));

			Attributes.Extension attributes = objects.newInstance(Attributes.Extension.class);
			sourceElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.VERIFICATION);
				details.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE).of(VerificationType.MAIN_SOURCES);
			}));
//			cppApiElements.configure(c -> attributes.of(c, details -> {
//				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.C_PLUS_PLUS_API);
////				details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of("none");
//			}));
			linkElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_LINK);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of("none");
			}));
			runtimeElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_RUNTIME);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of("none");
			}));
		}

		public NamedDomainObjectProvider<Configuration> getSourceElements() {
			return sourceElements;
		}

//		public NamedDomainObjectProvider<Configuration> getCppApiElements() {
//			return cppApiElements;
//		}

		public NamedDomainObjectProvider<Configuration> getLinkElements() {
			return linkElements;
		}

		public NamedDomainObjectProvider<Configuration> getRuntimeElements() {
			return runtimeElements;
		}
	}
}
