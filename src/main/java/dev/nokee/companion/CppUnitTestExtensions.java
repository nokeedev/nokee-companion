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
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderConvertible;
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
import org.gradle.util.GradleVersion;

import javax.annotation.Nullable;
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

		static abstract class LibElemCompatEx implements AttributeCompatibilityRule<String> {
			@Inject
			public LibElemCompatEx() {}

			@Override
			public void execute(CompatibilityCheckDetails<String> details) {
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

		@Override
		public void apply(Project project) {
//			project.getPluginManager().apply(CppBinaryConfigurationRule.class);
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
					testedComponent.configure(asHidden());
					testedComponent.configure(configuration -> {
						final Provider<ModuleDependency> dependency = testedComponentExtension.getTestedComponent().map(it -> {
							ModuleDependency result = dependencyFactory.create(project);
							result.capabilities(capabilities -> {
								capabilities.requireCapability(Capabilities.forProvider(testedComponentExtension.getTestableTypeProvider().map(TestableCapability::of)));
							});
							return result;
						});
						configuration.getDependencies().addAllLater(objects.listProperty(Dependency.class).value(dependency.map(Collections::singletonList).orElse(Collections.emptyList())));
					});
					testSuite.getImplementationDependencies().extendsFrom(testedComponent.get());

					testSuite.getBinaries().configureEach(CppTestExecutable.class, testExecutable -> {
						NamedDomainObjectProvider<Configuration> testedSources = configurationRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("testedSources")));
						testedSources.configure(configuration -> {
							configuration.extendsFrom(testedComponent.get());
						});
						testedSources.configure(attributes(objects, details -> {
							details.attribute(Usage.USAGE_ATTRIBUTE).of("native-compile");
							details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(optimizationOf(testExecutable)::get));
							details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(debuggabilityOf(testExecutable)::get));
							details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(testExecutable.getTargetMachine().getArchitecture());
							details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(testExecutable.getTargetMachine().getOperatingSystemFamily());
						}));
						cppSourceOf(testExecutable).mut(objects.fileCollection().from((Callable<?>) () -> {
							return testedComponentExtension.getTestableTypeProvider().map(testableType -> {
								if (testableType.equals("sources")) {
									return testedSources.map(it -> {
										ArtifactView view = it.getIncoming().artifactView(attributes(objects, attributes -> {
											attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE));
										}));
										return view.getArtifacts().getArtifactFiles();
									}).get().getAsFileTree();
								}
								return Collections.emptyList();
							}).orElse(Collections.emptyList());
						})::plus);

						linkTask(testExecutable).configure(task -> {
							task.getLibs().setFrom((Callable<?>) () -> {
								ArtifactView view = configurations.getByName(nativeLinkConfigurationName(testExecutable)).getIncoming().artifactView(attributes(objects, details -> {
									details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
								}));
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

	public static abstract class TestedComponentExtension {
		@Inject
		public TestedComponentExtension(ObjectFactory objects) {
			getTestableType().convention("objects");
			getTestableTypeProvider().set(getTestedComponent().zip(getTestableType(), (a,b) -> {
				if (a instanceof CppApplication && Arrays.asList("sources", "product").contains(b.toString())) {
					throw new UnsupportedOperationException(String.format("Cannot integrate as %s for application", b));
				}
				return b.toString();
			}));
		}

		protected abstract Property<ProductionCppComponent> getTestedComponent();

		protected abstract Property<Object> getTestableType();

		protected abstract Property<String> getTestableTypeProvider();

		public TestedComponentExtension from(Provider<? extends ProductionCppComponent> testedComponent) {
			getTestedComponent().set(testedComponent);
			return this;
		}

		public TestedComponentExtension linkAgainst(Object type) {
			getTestableType().set(type);
			return this;
		}

		public String getObjects() {
			return "objects";
		}

		public String getProduct() {
			return "product";
		}

		public String getSources() {
			return "sources";
		}
	}

	/*private*/ static abstract /*final*/ class TestableElementsPlugin implements Plugin<Project> {
		private final ObjectFactory objects;
		private final ProviderFactory providers;
		private final ConfigurationContainer configurations;
		private final TaskContainer tasks;
		private final ProjectLayout layout;

		@Inject
		public TestableElementsPlugin(ObjectFactory objects, ProviderFactory providers, ConfigurationContainer configurations, TaskContainer tasks, ProjectLayout layout) {
			this.objects = objects;
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
						final TestableExtension extension = objects.newInstance(TestableExtension.class, CppNames.of(binary).append("testable"));
						extension.getElements().configureEach(configureAttributes(component, binary));
						extension.getElements().configureEach(it -> {
							it.elements.all(outgoing(outgoing -> outgoing.capability(TestableCapability.of(it.getName()))));
						});

						Object exportedObjectsArtifactNotation = exportObjectsTask(component, binary);

						extension.getElements().create("sources", it -> {
							if (component instanceof CppLibrary) {
								it.getCompileElements().configure(outgoing(outgoing -> {
									outgoing.artifact(tasks.register(it.getNames().taskName("sync", "sources").toString(), Sync.class, task -> {
										task.from(CppSourceFiles.cppSourceOf(binary));
										task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
									}), spec -> spec.setType(directoryType(C_PLUS_PLUS_SOURCE_TYPE)));
								}));
							}
							it.getCppApiElements().configure(outgoing(outgoing -> {
								final TaskProvider<Sync> allHeadersTask = tasks.register(it.getNames().taskName("sync", "cppHeaders").toString(), Sync.class, task -> {
									task.from(component.getHeaderFiles());
									task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
								});
								outgoing.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_HEADER_TYPE)));
							}));

						});
						extension.getElements().create("objects", it -> {
							it.getCppApiElements().configure(outgoing(outgoing -> {
								final TaskProvider<Sync> allHeadersTask = tasks.register(it.getNames().taskName("sync", "cppHeaders").toString(), Sync.class, task -> {
									if (component instanceof CppLibrary) {
										task.from(((CppLibrary) component).getPublicHeaderFiles());
									} else {
										task.from(component.getHeaderFiles());
									}
									task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
								});
								outgoing.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_HEADER_TYPE)));
							}));
							it.getLinkElements().configure(outgoing(outgoing -> {
								outgoing.artifact(exportedObjectsArtifactNotation, spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
							}));
						});
						if (component instanceof CppLibrary) {
							extension.getElements().create("product", it -> {
								it.getCppApiElements().configure(outgoing(outgoing -> {
									TaskProvider<Sync> allHeadersTask = tasks.register(it.getNames().taskName("sync", "cppHeaders").toString(), Sync.class, task -> {
										task.from(((CppLibrary) component).getPublicHeaderFiles());
										task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
									});
									outgoing.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_HEADER_TYPE)));
								}));
								it.getLinkElements().configure(outgoing(outgoing -> {
									outgoing.getArtifacts().addAllLater(configurations.named(linkElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
								}));
								it.getRuntimeElements().configure(outgoing(outgoing -> {
									outgoing.getArtifacts().addAllLater(configurations.named(runtimeElementsConfigurationName(binary)).map(t -> t.getOutgoing().getArtifacts()));
								}));
							});
						}
						((ExtensionAware) binary).getExtensions().add("testable", extension);
					});
				});
			});
		}

		private Object exportObjectsTask(CppComponent component, CppBinary binary) {
			if (component instanceof CppLibrary) {
				return tasks.register(CppNames.of(binary).taskName("sync", "objects").toString(), Sync.class, task -> {
					task.from(objectsOf(binary));
					task.setDestinationDir(layout.getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
				});
			} else if (component instanceof CppApplication) {
				return tasks.register(CppNames.of(binary).taskName("relocateMain").toString(), UnexportMainSymbol.class, task -> {
					task.getObjects().from(objectsOf(binary));
					task.getOutputDirectory().set(layout.getBuildDirectory().dir("tmp/" + task.getName()));
				});
			} else {
				throw new UnsupportedOperationException();
			}
		}

		private Action<TestableElements> configureAttributes(ProductionCppComponent component, CppBinary binary) {
			return new Action<TestableElements>() {
				@Override
				public void execute(TestableElements testable) {
					testable.getConfigurations().all(attributes(objects, this::binaryAttributes));

					testable.getCompileElements().configure(it -> it.setDescription("Testable compile elements of " + binary + "."));
					testable.getCppApiElements().configure(it -> it.setDescription("Testable API elements of " + binary + "."));
					testable.getLinkElements().configure(it -> it.setDescription("Testable link elements of " + binary + "."));
					testable.getRuntimeElements().configure(it -> it.setDescription("Testable runtime elements of " + binary + "."));

					if (component instanceof CppLibrary) {
						testable.getConfigurations().all(attributes(objects, details -> details.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.valueOf(CppNames.qualifyingName(binary).get("linkageName").toString().toUpperCase(Locale.ENGLISH)))));
					}

					testable.getCppApiElements().configure(it -> {
						if (component instanceof CppApplication) {
							it.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						} else {
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
					testable.getRuntimeElements().configure(it -> it.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0])));
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

	/*private*/ static abstract /*final*/ class TestableExtension {
		private final NamedDomainObjectContainer<TestableElements> elements;

		@Inject
		public TestableExtension(ObjectFactory objects, Names names) {
			this.elements = objects.domainObjectContainer(TestableElements.class, name -> objects.newInstance(TestableElements.class, names.append(name), objects.newInstance(ConfigurationRegistry.class)));
		}

		public NamedDomainObjectContainer<TestableElements> getElements() {
			return elements;
		}
	}

	/*private*/ static abstract /*final*/ class TestableElements implements Named {
		private final NamedDomainObjectProvider<Configuration> compileElements;
		private final NamedDomainObjectProvider<Configuration> cppApiElements;
		private final NamedDomainObjectProvider<Configuration> linkElements;
		private final NamedDomainObjectProvider<Configuration> runtimeElements;
		private final NamedDomainObjectSet<Configuration> elements;
		private final Names names;

		@Inject
		public TestableElements(Names names, ConfigurationRegistry configurations, ObjectFactory objects, ProviderFactory providers) {
			this.names = names;
			this.compileElements = configurations.consumable(names.configurationName("compileElements"));
			this.cppApiElements = configurations.consumable(names.configurationName("cppApiElements"));
			this.linkElements = configurations.consumable(names.configurationName("linkElements"));
			this.runtimeElements = configurations.consumable(names.configurationName("runtimeElements"));

			this.elements = objects.namedDomainObjectSet(Configuration.class);
			elements.add(compileElements.get());
			elements.add(cppApiElements.get());
			elements.add(linkElements.get());
			elements.add(runtimeElements.get());

			elements.all(asHidden());

			compileElements.configure(attributes(objects, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of("native-compile");
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
				details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of("sources-cplusplus");
			}));
			cppApiElements.configure(attributes(objects, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.C_PLUS_PLUS_API);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
				details.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).of(LibraryElements.HEADERS_CPLUSPLUS);
			}));
			linkElements.configure(attributes(objects, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_LINK);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
			}));

			runtimeElements.configure(attributes(objects, details -> {
				details.attribute(Usage.USAGE_ATTRIBUTE).of(Usage.NATIVE_RUNTIME);
				details.attribute(Category.CATEGORY_ATTRIBUTE).of(Category.LIBRARY);
			}));
		}

		@Override
		public String getName() {
			return names.get("elementName").toString();
		}

		public Names getNames() {
			return names;
		}

		public NamedDomainObjectProvider<Configuration> getCompileElements() {
			return compileElements;
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

		public NamedDomainObjectSet<Configuration> getConfigurations() {
			return elements;
		}
	}

	private static final class TestableCapability {
		private static final String GROUP = "testable-type";
		private static final String VERSION = "1.0";

		public static String of(String name) {
			return GROUP + ":" + name + ":" + VERSION;
		}
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


	private static Action<Configuration> outgoing(Action<? super ConfigurationPublications> action) {
		return it -> it.outgoing(action);
	}

	private static Action<Configuration> asHidden() {
		return it -> it.setVisible(false);
	}

	private static class Capabilities {
		public static Object forProvider(Provider<? extends String> notation) {
			if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) >= 0) {
				return notation;
			} else {
				return new Capability() {
					@Override
					public String getGroup() {
						return notation.get().split(":")[0];
					}

					@Override
					public String getName() {
						return notation.get().split(":")[1];
					}

					@Override
					public @Nullable String getVersion() {
						return notation.get().split(":")[2];
					}
				};
			}
		}


		public static <T> Action<T> capabilities(ObjectFactory objects, Action<? super Details> action) {
			return it -> {
				if (it instanceof ModuleDependencyCapabilitiesHandler) {
					action.execute(new Details(new MinimalModuleDependencyCapabilitiesCollectionAdapter((ModuleDependencyCapabilitiesHandler) it)));
				} else if (it instanceof ConfigurationPublications) {
					action.execute(new Details(new MinimalConfigurationPublicationsCollectionAdapter((ConfigurationPublications) it)));
				} else {
					throw new UnsupportedOperationException();
				}
			};
		}

		@NonExtensible
		public static class Details {
			private final MinimalCapabilitiesCollection collection;

			public Details(MinimalCapabilitiesCollection collection) {
				this.collection = collection;
			}

			public void capability(String notation) {
				collection.addCapability(notation);
			}

			public void capability(Provider<? extends String> notation) {
				collection.addCapability(notation);
			}

			public void capability(ProviderConvertible<? extends String> notation) {
				collection.addCapability(notation.asProvider());
			}

			public void capabilities(String... notations) {
				for (String notation : notations) {
					collection.addCapability(notation);
				}
			}
		}

		private interface MinimalCapabilitiesCollection {
			void addCapability(String notation);

			void addCapability(Provider<? extends String> notation);

			void addFeature(String featureName);

			void addFeature(Provider<? extends String> featureName);
		}

		private static final class MinimalModuleDependencyCapabilitiesCollectionAdapter implements MinimalCapabilitiesCollection {
			private final ModuleDependencyCapabilitiesHandler delegate;

			private MinimalModuleDependencyCapabilitiesCollectionAdapter(ModuleDependencyCapabilitiesHandler delegate) {
				this.delegate = delegate;
			}

			@Override
			public void addCapability(String notation) {
				delegate.requireCapability(notation);
			}

			@Override
			public void addCapability(Provider<? extends String> notation) {
				delegate.requireCapability(notation);
			}

			@Override
			public void addFeature(String featureName) {
				delegate.requireFeature(featureName);
			}

			@Override
			public void addFeature(Provider<? extends String> featureName) {
				delegate.requireFeature(featureName.map(it -> it));
			}
		}

		private static final class MinimalConfigurationPublicationsCollectionAdapter implements MinimalCapabilitiesCollection {
			private final ConfigurationPublications delegate;

			private MinimalConfigurationPublicationsCollectionAdapter(ConfigurationPublications delegate) {
				this.delegate = delegate;
			}

			@Override
			public void addCapability(String notation) {
				delegate.capability(notation);
			}

			@Override
			public void addCapability(Provider<? extends String> notation) {
				delegate.capability(notation);
			}

			@Override
			public void addFeature(String featureName) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void addFeature(Provider<? extends String> featureName) {
				throw new UnsupportedOperationException();
			}
		}
	}
}
