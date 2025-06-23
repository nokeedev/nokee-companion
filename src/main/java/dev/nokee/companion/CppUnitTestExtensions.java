package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.backports.DependencyFactory;
import dev.nokee.commons.backports.DependencyModifier;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.attributes.Attributes;
import dev.nokee.commons.names.CppNames;
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
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.*;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.cpp.*;
import org.gradle.language.nativeplatform.tasks.UnexportMainSymbol;
import org.gradle.language.swift.tasks.internal.SymbolHider;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.nokee.commons.gradle.SpecUtils.named;
import static dev.nokee.commons.names.CppNames.*;

/**
 * Represents missing properties that matches the tested component/binary for C++ test suites.
 */
public final class CppUnitTestExtensions {
	@SuppressWarnings("UnstableApiUsage")
	static abstract /*final*/ class Rule implements Plugin<Project> {
		private static final String OBJECT_CODE_TYPE = "public.object-code";
		private static final String C_PLUS_PLUS_SOURCE_TYPE = "public.c-plus-plus-source";

		private final ObjectFactory objects;
		private final ProviderFactory providers;
		private final TaskContainer tasks;
		private final ConfigurationContainer configurations;
		private final DependencyFactory dependencyFactory;
		private final ConfigurationRegistry configurationRegistry;
		private final ProjectLayout layout;
		private final CppEcosystemUtilities access;

		@Inject
		public Rule(ObjectFactory objects, ProviderFactory providers, TaskContainer tasks, ConfigurationContainer configurations, ProjectLayout layout, Project project) {
			this.objects = objects;
			this.providers = providers;
			this.tasks = tasks;
			this.configurations = configurations;
			this.dependencyFactory = objects.newInstance(DependencyFactory.class);
			this.configurationRegistry = objects.newInstance(ConfigurationRegistry.class);
			this.layout = layout;
			this.access = CppEcosystemUtilities.forProject(project);
		}

		/*private*/ static abstract /*final*/ class UnexportSymbolsTransform implements TransformAction<TransformParameters.None> {
			private final ExecOperations execOperations;

			@InputArtifact
			@PathSensitive(PathSensitivity.NAME_ONLY)
			public abstract Provider<FileSystemLocation> getInputArtifact();

			@Inject
			public UnexportSymbolsTransform(ExecOperations execOperations) {
				this.execOperations = execOperations;
			}

			@Override
			public void transform(TransformOutputs outputs) {
				unexportMainSymbol(getInputArtifact().get().getAsFile(), outputs);
			}

			private void unexportMainSymbol(File object, TransformOutputs outputs) {
				final File relocatedObject = relocatedObject(object, outputs);
				if (OperatingSystem.current().isWindows()) {
					try {
						final SymbolHider symbolHider = new SymbolHider(object);
						symbolHider.hideSymbol("main");     // 64 bit
						symbolHider.hideSymbol("_main");    // 32 bit
						symbolHider.hideSymbol("wmain");    // 64 bit
						symbolHider.hideSymbol("_wmain");   // 32 bit
						symbolHider.saveTo(relocatedObject);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				} else {
					execOperations.exec(new Action<ExecSpec>() {
						@Override
						public void execute(ExecSpec execSpec) {
							// TODO: should use target platform to make this decision
							if (OperatingSystem.current().isMacOsX()) {
								execSpec.executable("ld"); // TODO: Locate this tool from a tool provider
								execSpec.args(object);
								execSpec.args("-o", relocatedObject);
								execSpec.args("-r"); // relink, produce another object file
								execSpec.args("-unexported_symbol", "_main"); // hide _main symbol
							} else if (OperatingSystem.current().isLinux()) {
								execSpec.executable("objcopy"); // TODO: Locate this tool from a tool provider
								execSpec.args("-L", "main"); // hide main symbol
								execSpec.args(object);
								execSpec.args(relocatedObject);
							} else {
								throw new IllegalStateException("Do not know how to unexport a main symbol on " + OperatingSystem.current());
							}
						}
					});
				}
			}

			private File relocatedObject(File object, TransformOutputs outputs) {
				return outputs.file(object.getName());
			}
		}

		/*private*/ static abstract /*final*/ class UnpackObjectFiles implements TransformAction<TransformParameters.None> {
			@Inject
			public UnpackObjectFiles() {}

			@InputArtifact
			public abstract Provider<FileSystemLocation> getInputArtifact();

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

		/*private*/ static abstract /*final*/ class UnexportMainFunctionTransform implements TransformAction<TransformParameters.None> {
			@Inject
			public UnexportMainFunctionTransform() {}

			@Override
			public void transform(TransformOutputs outputs) {
				throw new UnsupportedOperationException("Cannot integrate as sources for application");
			}
		}

		/*private*/ static abstract /*final*/ class NativeArtifactTypeDefinitionCompatibilityRule implements AttributeCompatibilityRule<String> {
			@Inject
			public NativeArtifactTypeDefinitionCompatibilityRule() {}

			public void execute(CompatibilityCheckDetails<String> details) {
				String consumerValue = details.getConsumerValue();
				String producerValue = details.getProducerValue();
				if (consumerValue == null) {
					details.compatible();
				} else if (consumerValue.equals("dev.nokee.linkable-objects")) {
					if (!directoryType(OBJECT_CODE_TYPE).equals(producerValue)) {
						details.compatible();
					}
				}
			}
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				registerTestedComponentModifierOnDependencies(project);
				detachTestedComponentFromCppUnitTest(project);
				declareTestedComponentDependencyIfAbsent(project);

				project.getDependencies().registerTransform(UnpackObjectFiles.class, spec -> {
					spec.getFrom()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(OBJECT_CODE_TYPE))
						;
					spec.getTo()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
						;
				});
				project.getDependencies().registerTransform(UnexportSymbolsTransform.class, spec -> {
					spec.getFrom()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
						.attribute(Attribute.of("testable", String.class), "no")
					;
					spec.getTo()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
						.attribute(Attribute.of("testable", String.class), "yes")
					;
				});
				project.getDependencies().registerTransform(UnexportMainFunctionTransform.class, spec -> {
					spec.getFrom()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE))
						.attribute(Attribute.of("testable", String.class), "no")
					;
					spec.getTo()
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE))
						.attribute(Attribute.of("testable", String.class), "yes")
					;
				});
				project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getCompatibilityRules().add(NativeArtifactTypeDefinitionCompatibilityRule.class);

				project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
					NamedDomainObjectProvider<Configuration> apiElements = configurationRegistry.consumable(CppNames.of(component).configurationName("testableCppApiElements"));
					apiElements.configure(configuration -> {
						configuration.extendsFrom(configurations.getByName(implementationConfigurationName(component)));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.C_PLUS_PLUS_API));
						});
						configuration.outgoing(outgoing -> {
							outgoing.capability("test-elements:test-elements:1.0");
							outgoing.artifact(tasks.register(CppNames.of(component).taskName("sync", "headersTestableElements").toString(), Sync.class, task -> {
								task.from(component.getHeaderFiles());
								task.into(layout.getBuildDirectory().dir("tmp/" + task.getName()));
							}), spec -> spec.setType(ArtifactTypeDefinition.DIRECTORY_TYPE));
						});
					});
					NamedDomainObjectProvider<Configuration> sourcesElements = configurationRegistry.consumable(CppNames.of(component).configurationName("testableSourceElements"));
					sourcesElements.configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "native-compile"));
						});
						configuration.outgoing(outgoing -> {
							outgoing.capability("test-elements:test-elements:1.0");
							outgoing.getVariants().create(TestIntegrationType.SOURCE_LEVEL, variant -> {
								variant.artifact(tasks.register(CppNames.of(component).taskName("sync", "sourcesTestableElements").toString(), Sync.class, task -> {
									task.from(access.cppSourceOf(component));
									task.into(layout.getBuildDirectory().dir("tmp/" + task.getName()));
								}), spec -> spec.setType(directoryType(C_PLUS_PLUS_SOURCE_TYPE)));
								variant.attributes(attributes -> {
									attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.SOURCE_LEVEL));
									if (component instanceof CppApplication) {
										attributes.attribute(Attribute.of("testable", String.class), "no");
									}
								});
							});
							outgoing.getVariants().create(TestIntegrationType.LINK_LEVEL, variant -> {
								variant.attributes(attributes -> {
									attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.LINK_LEVEL));
								});
							});
							outgoing.getVariants().create(TestIntegrationType.PRODUCT_LEVEL, variant -> {
								variant.attributes(attributes -> {
									attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.PRODUCT_LEVEL));
								});
							});
						});
					});
					component.getBinaries().configureEach(binary -> {
						NamedDomainObjectProvider<Configuration> linkElements = configurationRegistry.consumable(CppNames.of(binary).configurationName("testableLinkElements"));
						linkElements.configure(configuration -> {
							// TODO: Don't forget to add linkOnly bucket when available
							configuration.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
							configuration.attributes(attributes -> {
								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_LINK));
								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());
								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
								if (component instanceof CppLibrary) {
									attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, binary instanceof CppSharedLibrary ? Linkage.SHARED : Linkage.STATIC);
								}
							});
							configuration.outgoing(outgoing -> {
								outgoing.capability("test-elements:test-elements:1.0");
								outgoing.getVariants().create(TestIntegrationType.SOURCE_LEVEL, variant -> {
									variant.attributes(attributes -> {
										attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.SOURCE_LEVEL));
									});
								});
								outgoing.getVariants().create(TestIntegrationType.LINK_LEVEL, variant -> {
									variant.artifact(tasks.register(CppNames.of(binary).taskName("sync", "objectsTestableElements").toString(), Sync.class, task -> {
										task.from(access.objectsOf(binary));
										task.into(layout.getBuildDirectory().dir("tmp/" + task.getName()));
									}), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
									variant.attributes(attributes -> {
										attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.LINK_LEVEL));
										if (component instanceof CppApplication) {
											attributes.attribute(Attribute.of("testable", String.class), "no");
										}
									});
								});
								if (component instanceof CppLibrary) {
									outgoing.getVariants().create(TestIntegrationType.PRODUCT_LEVEL, variant -> {
										variant.getArtifacts().addAll(configurations.getByName(linkElementsConfigurationName(binary)).getArtifacts());
										variant.attributes(attributes -> {
											attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.PRODUCT_LEVEL));
										});
									});
								}
							});
						});

						NamedDomainObjectProvider<Configuration> runtimeElements = configurationRegistry.consumable(CppNames.of(binary).configurationName("testableRuntimeElements"));
						runtimeElements.configure(configuration -> {
							// TODO: Don't forget to add runtimeOnly bucket when available
							configuration.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
							configuration.attributes(attributes -> {
								attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_RUNTIME));
								attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());
								attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
								if (component instanceof CppLibrary) {
									attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, binary instanceof CppSharedLibrary ? Linkage.SHARED : Linkage.STATIC);
								}
							});
							configuration.outgoing(outgoing -> {
								outgoing.capability("test-elements:test-elements:1.0");
								outgoing.getVariants().create(TestIntegrationType.SOURCE_LEVEL, variant -> {
									variant.attributes(attributes -> {
										attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.SOURCE_LEVEL));
									});
								});
								outgoing.getVariants().create(TestIntegrationType.LINK_LEVEL, variant -> {
									variant.attributes(attributes -> {
										attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.LINK_LEVEL));
									});
								});
								outgoing.getVariants().create(TestIntegrationType.PRODUCT_LEVEL, variant -> {
									if (component instanceof CppLibrary) {
										variant.getArtifacts().addAll(configurations.getByName(runtimeElementsConfigurationName(binary)).getArtifacts());
									} else {
										variant.getArtifacts().add(new UnpublishableArtifact());
									}
									variant.attributes(attributes -> {
										attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.PRODUCT_LEVEL));
									});
								});
							});
						});
					});
				});

				project.getComponents().withType(CppTestExecutable.class).configureEach(testExecutable -> {
					NamedDomainObjectProvider<? extends Configuration> cppSources = configurationRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("cppSources")));
					cppSources.configure(configuration -> {
						configuration.extendsFrom(configurations.getByName(implementationConfigurationName(testExecutable)));
					});
					cppSources.configure(attributes(objects, details -> {
						details.attribute(Usage.USAGE_ATTRIBUTE).of("native-compile");
						details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(access.optimizationOf(testExecutable)::get));
						details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(access.debuggabilityOf(testExecutable)::get));
						details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(testExecutable.getTargetMachine().getArchitecture());
						details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(testExecutable.getTargetMachine().getOperatingSystemFamily());

						details.attribute(Attribute.of("testable", String.class), "yes");
						details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE));
					}));
					access.cppSourceOf(testExecutable).mut(objects.fileCollection().from((Callable<?>) () -> {
						boolean hasSource = cppSources.get().getAllDependencies().stream().anyMatch(it -> {
							if (it instanceof ModuleDependency) {
								TestIntegrationType type = ((ModuleDependency) it).getAttributes().getAttribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE);
								if (type != null) {
									return type.getName().equals(TestIntegrationType.SOURCE_LEVEL);
								}
							}
							return false;
						});

						if (hasSource) {
							return cppSources.get().getAsFileTree();
						} else {
							return Collections.emptyList();
						}
					})::plus);

					configurations.named(nativeLinkConfigurationName(testExecutable)).configure(configurations -> {
						configurations.attributes(attributes -> {
							attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
							attributes.attribute(Attribute.of("testable", String.class), "yes");
						});
					});
				});
			});
		}

		private void detachTestedComponentFromCppUnitTest(Project project) {
			project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
				// Detach compileIncludePath from testedComponent
				testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
					final FileCollection includeDirs = objects.fileCollection().from((Callable<?>) () -> {
						ArtifactView view = configurations.getByName(cppCompileConfigurationName(testExecutable)).getIncoming().artifactView(attributes(objects, details -> {
							details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
						}));
						return view.getFiles();
					});
					final ShadowProperty<FileCollection> compileIncludePath = access.compileIncludePathOf(testExecutable);
					compileIncludePath.set(testSuite.getPrivateHeaderDirs().plus(includeDirs));
				});

				// Detach implementation configuration from testedComponent
				testSuite.getBinaries().whenElementFinalized(CppTestExecutable.class, testExecutable -> {
					configurations.named(implementationConfigurationName(testExecutable)).configure(it -> {
						it.setExtendsFrom(it.getExtendsFrom().stream().filter(t -> !(t.getName().startsWith("main") && t.getName().endsWith("Implementation"))).collect(Collectors.toList()));
					});
				});

				// Detach testedObjects from nativeLinkTest
				testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
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

				// Deprecate the unexport main symbol task
				testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testExecutable -> {
					// Note: we don't disable the task, for now, as someone else may be using it.
					//   In the future, we may want to throw an exception if the task is found in the task graph.
					tasks.withType(UnexportMainSymbol.class).configureEach(named(relocateMainForBinaryTaskName(testExecutable)::equals).whenSatisfied(task -> {
						// hide the task
						task.setGroup(null);

						// strongly suggest to not use the task
						task.setDescription("[deprecated] " + task.getDescription());
					}));
				});
			});
		}

		private void declareTestedComponentDependencyIfAbsent(Project project) {
			project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
				// Add implementation dependency to main component, if not already provided
				configurations.named(implementationConfigurationName(testSuite)).configure(configuration -> {
					configuration.withDependencies(new Action<DependencySet>() {
						@Override
						public void execute(DependencySet dependencies) {
							if (!hasTestedComponentDependency(dependencies) && hasMainNativeComponent()) {
								final ProjectDependency testedComponent = dependencyFactory.create(project);
								testedComponent.attributes(attributes -> {
									attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.LINK_LEVEL));
								});
								testedComponent.capabilities(capabilities -> {
									capabilities.requireCapability("test-elements:test-elements:1.0");
								});
								dependencies.add(testedComponent);
							}
						}

						private boolean hasTestedComponentDependency(DependencySet dependencies) {
							return dependencies.stream().flatMap(it -> dependencyProjectPathOf(it).map(Stream::of).orElseGet(Stream::empty)).anyMatch(project.getPath()::equals);
						}

						private boolean hasMainNativeComponent() {
							return project.getPlugins().hasPlugin("cpp-application") || project.getPlugins().hasPlugin("cpp-library");
						}
					});
				});
			});
		}

		private void registerTestedComponentModifierOnDependencies(Project project) {
			project.getDependencies().getExtensions().getExtraProperties().set("testedComponent", objects.newInstance(Closure.class));
			project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
				((ExtensionAware) testSuite.getDependencies()).getExtensions().getExtraProperties().set("testedComponent", objects.newInstance(Closure.class));
			});
		}

		/*private*/ static abstract class Closure implements TestedComponentDependency.Modifier {
			private final DependencyFactory dependencyFactory;
			private final ObjectFactory objects;
			private final ProviderFactory providers;

			@Inject
			public Closure(ObjectFactory objects, ProviderFactory providers) {
				this.dependencyFactory = objects.newInstance(DependencyFactory.class);
				this.objects = objects;
				this.providers = providers;
			}

			@Override
			public TestedComponentDependency modify(ProjectDependency dependency) {
				return modify(providers.provider(() -> dependency));
			}

			private <DependencyType extends ModuleDependency> TestedComponentDependency modify(Provider<? extends DependencyType> dependencyProvider) {
				return objects.newInstance(DefaultTestedComponentDependency.class, dependencyProvider);
			}

			@Override
			public TestedComponentDependency modify(Project project) {
				return modify(dependencyFactory.create(project));
			}

			// manual Groovy DSL decoration
			public TestedComponentDependency call(Project project) {
				return modify(project);
			}
		}

		@NonExtensible
		/*private*/ static abstract class DefaultTestedComponentDependency implements TestedComponentDependency {
			private final Provider<ModuleDependency> dependencyProvider;
			private final ObjectFactory objects;

			@Inject
			public DefaultTestedComponentDependency(Provider<ModuleDependency> dependencyProvider, ObjectFactory objects) {
				this.dependencyProvider = dependencyProvider;
				this.objects = objects;
			}

			public ModuleDependency asSources() {
				return dependencyProvider.map(objects.newInstance(DependencyModifier.class, (DependencyModifier.Action) dependency -> {
					dependency.attributes(attributes -> {
						attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.SOURCE_LEVEL));
					});
					dependency.capabilities(capabilities -> {
						capabilities.requireCapability("test-elements:test-elements:1.0");
					});
				})::modify).get();
			}

			public ModuleDependency asObjects() {
				return dependencyProvider.map(objects.newInstance(DependencyModifier.class, (DependencyModifier.Action) dependency -> {
					dependency.attributes(attributes -> {
						attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.LINK_LEVEL));
					});
					dependency.capabilities(capabilities -> {
						capabilities.requireCapability("test-elements:test-elements:1.0");
					});
				})::modify).get();
			}

			public ModuleDependency asProduct() {
				return dependencyProvider.map(objects.newInstance(DependencyModifier.class, (DependencyModifier.Action) dependency -> {
					dependency.attributes(attributes -> {
						attributes.attribute(TestIntegrationType.TEST_INTEGRATION_TYPE_ATTRIBUTE, objects.named(TestIntegrationType.class, TestIntegrationType.PRODUCT_LEVEL));
					});
					dependency.capabilities(capabilities -> {
						capabilities.requireCapability("test-elements:test-elements:1.0");
					});
				})::modify).get();
			}

			@Override
			public Provider<ModuleDependency> asProvider() {
				return dependencyProvider;
			}
		}

		private static class UnpublishableArtifact implements PublishArtifact {
			@Override
			public String getName() {
				return "dummy";
			}

			@Override
			public String getExtension() {
				return "";
			}

			@Override
			public String getType() {
				return "";
			}

			@Override
			public @Nullable String getClassifier() {
				return null;
			}

			@Override
			public File getFile() {
				return new File("dummy.txt");
			}

			@Override
			public @Nullable Date getDate() {
				return null;
			}

			@Override
			public TaskDependency getBuildDependencies() {
				return new TaskDependency() {
					@Override
					public Set<? extends Task> getDependencies(@Nullable Task task) {
						throw new UnsupportedOperationException("Cannot integrate as product for application");
					}
				};
			}
		}
	}

	private static Optional<String> dependencyProjectPathOf(Dependency dependency) {
		if (dependency instanceof ProjectDependency) {
			if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) >= 0) {
				return Optional.of(((ProjectDependency) dependency).getPath());
			} else {
				@SuppressWarnings("deprecation")
				final Project dependencyProject = ((ProjectDependency) dependency).getDependencyProject();
				return Optional.of(dependencyProject.getPath());
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

	private static String directoryType(String type) {
		return type + "-directory";
	}
}
