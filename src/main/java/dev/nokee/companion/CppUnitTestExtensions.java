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
import org.gradle.nativeplatform.tasks.InstallExecutable;
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

		static abstract class LibElemDisam implements AttributeDisambiguationRule<LibraryElements> {
			@Inject
			public LibElemDisam() {}

			@Override
			public void execute(MultipleCandidatesDetails<LibraryElements> details) {
				Map<String, LibraryElements> vals = details.getCandidateValues().stream().collect(Collectors.toMap(Named::getName, it -> it));
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


		static abstract class LibElemDisamEx implements AttributeDisambiguationRule<String> {
			@Inject
			public LibElemDisamEx() {}

			@Override
			public void execute(MultipleCandidatesDetails<String> details) {
				System.out.println("DISAM " + details);
				if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
					if (details.getCandidateValues().contains("dev.nokee.linkable-objects")) {
						details.closestMatch("dev.nokee.linkable-objects");
					} else if (details.getCandidateValues().contains("com.apple.mach-o-dylib")) {
						details.closestMatch("com.apple.mach-o-dylib");
					}
				}
			}
		}

		static abstract class LibElemCompatEx implements AttributeCompatibilityRule<String> {
			@Inject
			public LibElemCompatEx() {}

			@Override
			public void execute(CompatibilityCheckDetails<String> details) {
				System.out.println("COMAPT " + details.getProducerValue() + " -- " + details.getConsumerValue());
				if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
					System.out.println("WAT??");
					if (Arrays.asList("dylib", "com.apple.mach-o-dylib", "public.object-code"/*, "public.object-code-directory"*/).contains(details.getProducerValue())) {
						details.compatible();
					}
//				} else if (details.getConsumerValue().equals("public.object-code-directory")) {
//					if (Arrays.asList("public.object-code").contains(details.getProducerValue())) {
//						details.compatible();
//					}
				}
			}
		}

		@Override
		public void apply(Project project) {
			project.getPluginManager().apply(CppBinaryConfigurationRule.class);
			project.getPluginManager().apply(CppBinaryProperties.Rule.class);
			project.getPluginManager().apply(TestableElementsPlugin.class);

			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
					component.getBinaries().configureEach(CppBinary.class, binary -> {
						if (binary instanceof ComponentWithLinkFile) {
							configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
//									outgoing.getArtifacts().clear();
//									outgoing.artifact(((ComponentWithLinkFile) binary).getLinkFile(), it -> it.setType("com.apple.mach-o-dylib"));
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
//										artifact.setType("public.unix-shared-library"); // FIXME: set correct type
										System.out.println("HERE??? " + artifact.getName() + " -- " + artifact.getType());
										artifact.setType("com.apple.mach-o-dylib"); // FIXME: set correct type
										System.out.println("HERE??? " + artifact.getName() + " -- " + artifact.getType());
									});
								});
							});
						}

						if (binary instanceof ComponentWithRuntimeFile) {
							configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
//										artifact.setType("public.unix-executable"); // FIXME: set correct type
										artifact.setType("com.apple.mach-o-dylib"); // FIXME: set correct type
									});
								});
							});
						}

						if (binary instanceof ComponentWithExecutable) {
							configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
//										artifact.setType("public.unix-executable"); // FIXME: set correct type
										artifact.setType("com.apple.mach-o-executable"); // FIXME: set correct type
									});
								});
							});
						}
					});
				});
			});


			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getDependencies().getAttributesSchema().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE);

				project.getDependencies().registerTransform(UnpackObjectFiles.class, spec -> {
					spec.getFrom()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code-directory")
						;
					spec.getTo()
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
						;
				});

				project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getCompatibilityRules().add(LibElemCompatEx.class);
				project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getDisambiguationRules().add(LibElemDisamEx.class);

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
								attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.c-plus-plus-source-directory");
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

//						configurations.named(nativeRuntimeConfigurationName(testExecutable)).configure(configuration -> {
//							configuration.attributes(attributes -> {
//								attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "dynamic-lib"));
//							});
//						});

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

							ArtifactView view = configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().artifactView(it -> {
								it.attributes(attributes -> {
									attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
								});
							});
							task.getLibs().setFrom(view.getArtifacts().getResolvedArtifacts().map(it -> {
								List<Object> result = new ArrayList<>();
								for (ResolvedArtifactResult t : it) {
									System.out.println(t);
									System.out.println(objects.newInstance(Attributes.Extension.class).of(t.getVariant()).getAsMap().get());
									System.out.println(t.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE));
									result.add(t.getFile());
								}
								return result;
							}));
							task.getLibs().builtBy(view.getFiles());

//							task.getLibs().setFrom(configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().getArtifacts().getResolvedArtifacts().map(it -> {
//								List<Object> result = new ArrayList<>();
//								for (ResolvedArtifactResult t : it) {
////									System.out.println(t);
////									System.out.println(objects.newInstance(Attributes.Extension.class).of(t.getVariant()).getAsMap().get());
////									System.out.println(t.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE));
//									if (t.getVariant().getAttributes().getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).equals("public.object-code-directory")) {
//										result.add(objects.fileCollection().from(t.getFile()).getAsFileTree());
//									} else {
//										result.add(t.getFile());
//									}
//								}
//								return result;
//							}));
						});
						tasks.named(installTaskName(testExecutable), InstallExecutable.class).configure(task -> {
							task.setLibs(configurations.getByName(nativeRuntimeConfigurationName(testExecutable)).getIncoming().getArtifacts().getArtifactFiles());
						});
					});
				});

				project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
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
						//   Instead, we remove any current and future, FileCollectionDependency that doesn't match our testableObjects.
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
						details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(optimizationOf(binary)::get));
						details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(debuggabilityOf(binary)::get));
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
							}), spec -> spec.setType("public.c-plus-plus-source-directory"));
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
					testable.getLinkElements().configure(it -> attributes.of(it, details -> {
//						details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of("linkable-objects");
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
							}), spec -> spec.setType("public.object-code-directory"));
						});
						it.getVariants().create("library", variant -> {
							attributes.of(variant, details -> {
								details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.DYNAMIC_LIB);
							});
							if (component instanceof CppLibrary) {
								variant.artifact(((ComponentWithLinkFile) binary).getLinkFile(), t -> t.setType("com.apple.mach-o-dylib"));  // TODO: SHOULD NOT EXISTS ON APP
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
					details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(debuggabilityOf(binary)::get));
					details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(optimizationOf(binary)::get));
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
//				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
//				details.attribute(Attribute.of("dev.nokee.testable-type", String.class)).of("none");
			}));
			runtimeElements.configure(c -> attributes.of(c, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_RUNTIME);
//				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
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
