package dev.nokee.companion;

import dev.nokee.commons.backports.ConfigurationRegistry;
import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.gradle.attributes.Attributes;
import dev.nokee.commons.names.CppNames;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.*;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithRuntimeUsage;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.tasks.UnexportMainSymbol;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.plugins.CppUnitTestPlugin;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryObjects.objectsOf;
import static dev.nokee.companion.CppBinaryProperties.debuggabilityOf;
import static dev.nokee.companion.CppBinaryProperties.optimizationOf;
import static dev.nokee.companion.CppBinaryTaskExtensions.linkTask;
import static dev.nokee.companion.CppSourceFiles.cppSourceOf;
import static dev.nokee.companion.NativeArtifactTypeDefinition.*;

abstract /*final*/ class CppBinaryConfigurationRule implements Plugin<Project> {
	private final ConfigurationContainer configurations;
	private final ObjectFactory objects;
	private final TaskContainer tasks;
	private final ProviderFactory providers;
	private final ConfigurationRegistry bucketRegistry;

	@Inject
	public CppBinaryConfigurationRule(ConfigurationContainer configurations, ObjectFactory objects, TaskContainer tasks, ProviderFactory providers) {
		this.configurations = configurations;
		this.objects = objects;
		this.tasks = tasks;
		this.providers = providers;
		this.bucketRegistry = objects.newInstance(ConfigurationRegistry.class);
	}

	public static abstract class ObjectsCompatibilityRule implements AttributeCompatibilityRule<LibraryElements> {
		private final String description;

		@Inject
		public ObjectsCompatibilityRule(String description) {
			this.description = description;
		}

		@Override
		public void execute(CompatibilityCheckDetails<LibraryElements> details) {
			System.out.println("COMAP " + description + " => " + details.getConsumerValue() + " -- " + details.getProducerValue());
			if (details.getConsumerValue().getName().equals(LibraryElements.OBJECTS) && Arrays.asList(LibraryElements.LINK_ARCHIVE, LibraryElements.HEADERS_CPLUSPLUS, LibraryElements.DYNAMIC_LIB, "testable-objects").contains(details.getProducerValue().getName())) {
				details.compatible();
			} else if (details.getConsumerValue().getName().equals("sources") && Arrays.asList(LibraryElements.HEADERS_CPLUSPLUS).contains(details.getProducerValue().getName())) {
				details.compatible();
			}
		}
	}

	public static abstract class ObjectsDisambiguationRule implements AttributeDisambiguationRule<LibraryElements> {
		private final String description;

		@Inject
		public ObjectsDisambiguationRule(String description) {
			this.description = description;
		}

		@Override
		public void execute(MultipleCandidatesDetails<LibraryElements> details) {
			System.out.println("DISAMBIGUIATION " + description + " => " + details.getConsumerValue() + " -- " + details.getCandidateValues());
			if (details.getConsumerValue() == null) {
				Map<String, LibraryElements> values = details.getCandidateValues().stream().collect(Collectors.toMap(it -> it.getName(), it -> it));
//				if (values.keySet().equals(new HashSet<String>() {{
//					add(LibraryElements.HEADERS_CPLUSPLUS);
//					add("testable-" + LibraryElements.HEADERS_CPLUSPLUS);
//				}})) {
//					details.closestMatch(values.get(LibraryElements.HEADERS_CPLUSPLUS));
//				}

				if (values.containsKey(LibraryElements.OBJECTS) || values.keySet().stream().anyMatch(NativeArtifactTypeDefinition::isObjectsCompatibleType)) {
					if (values.containsKey(LibraryElements.DYNAMIC_LIB)) {
						details.closestMatch(values.get(LibraryElements.DYNAMIC_LIB));
					} else if (values.containsKey(LibraryElements.LINK_ARCHIVE)) {
						details.closestMatch(values.get(LibraryElements.LINK_ARCHIVE));
					}
				}
			} else {
				if (details.getCandidateValues().contains(details.getConsumerValue())) {
					details.closestMatch(details.getConsumerValue());
				}
			}
		}
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

	private TaskProvider<Sync> objects(CppBinary binary) {
		String taskName = CppNames.of(binary).taskName("export", "objects").toString();
		if (tasks.getNames().contains(taskName)) {
			return tasks.named(taskName, Sync.class);
		} else {
			return tasks.register(taskName, Sync.class, task -> {
				task.from(objectsOf(binary));
				task.into(task.getProject().getLayout().getBuildDirectory().dir("tmp/" + task.getName()));
			});
		}
	}

	static abstract class UnpackObjectFiles implements TransformAction<TransformParameters.None> {
		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Inject
		public UnpackObjectFiles() {}

		@Override
		public void transform(TransformOutputs outputs) {
			System.out.println("UNPACK OBJECTS " + getInputArtifact().get().getAsFile());
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

	static abstract class UnexportFiles implements TransformAction<TransformParameters.None> {
		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Inject
		public UnexportFiles() {}

		@Override
		public void transform(TransformOutputs outputs) {
			System.out.println("UNEXPORT OBJECTS " + getInputArtifact().get().getAsFile());
			outputs.file(getInputArtifact().get());
			throw new UnsupportedOperationException();
		}
	}

	static abstract class LibElemCompatEx implements AttributeCompatibilityRule<String> {
		@Inject
		public LibElemCompatEx() {}

		@Override
		public void execute(CompatibilityCheckDetails<String> details) {
			System.out.println("COMPAT " + details.getConsumerValue() + " ---->>>> " + details.getProducerValue());
			if (details.getConsumerValue().equals("dev.nokee.linkable-objects")) {
				if (Arrays.asList(LINKABLE_TYPES).contains(details.getProducerValue())) {
					details.compatible();
				}
//			} else if (details.getConsumerValue().equals("dev.nokee.runnable-objects")) {
//				if (Arrays.asList(RUNNABLE_TYPES).contains(details.getProducerValue())) {
//					details.compatible();
//				}
			}
		}
	}

	public static abstract class IdentityTransform implements TransformAction<TransformParameters.None> {

		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Override
		public void transform(TransformOutputs outputs) {
			File input = getInputArtifact().get().getAsFile();
			System.out.println("IDENTITY " + input);
			if (input.isDirectory()) {
				outputs.dir(input);
			} else if (input.isFile()) {
				outputs.file(input);
			} else {
				throw new RuntimeException("Expecting a file or a directory: " + input.getAbsolutePath());
			}
		}
	}


	@Override
	@SuppressWarnings("UnstableApiUsage")
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getDependencies().attributesSchema(schema -> {
				schema.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, strategy -> {
					strategy.getCompatibilityRules().add(ObjectsCompatibilityRule.class, it -> it.params(project.toString()));
					strategy.getDisambiguationRules().add(ObjectsDisambiguationRule.class, it -> it.params(project.toString()));
				});
			});

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

//			project.getDependencies().registerTransform(IdentityTransform.class, spec -> {
//				spec.getFrom()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
//					;
//				spec.getTo()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects")
//					;
//			});
//
//			project.getDependencies().registerTransform(IdentityTransform.class, spec -> {
//				spec.getFrom()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, SO_TYPE)
//				;
//				spec.getTo()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects")
//				;
//			});
//
//			project.getDependencies().registerTransform(IdentityTransform.class, spec -> {
//				spec.getFrom()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, DYLIB_TYPE)
//				;
//				spec.getTo()
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects")
//				;
//			});
//
//			project.getDependencies().registerTransform(UnexportFiles.class, spec -> {
//				spec.getFrom()
//					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
//					.attribute(Attribute.of("testable", String.class), "no")
////					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(OBJECT_CODE_TYPE))
//				;
//				spec.getTo()
//					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS))
//					.attribute(Attribute.of("testable", String.class), "yes")
////					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
//				;
//			});

			project.getDependencies().getAttributesSchema().attributeDisambiguationPrecedence(Usage.USAGE_ATTRIBUTE);
			project.getDependencies().getAttributesSchema().attributeDisambiguationPrecedence(CppBinary.LINKAGE_ATTRIBUTE);
			project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getCompatibilityRules().add(LibElemCompatEx.class);
			project.getDependencies().getAttributesSchema().attribute(Attribute.of("testable", String.class));

			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				if (binary instanceof CppStaticLibrary) {
					configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, libraryElementsOf(binary)));
						});
					});
					bucketRegistry.consumable(CppNames.of(binary).configurationName("linkObjectsElements")).configure(configuration -> {
						// TODO: May have to defer
						configuration.extendsFrom(configurations.getByName(linkElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_LINK));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, project.provider(debuggabilityOf(binary)));
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, project.provider(optimizationOf(binary)));
							attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
							attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());

							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS));
						});
						configuration.outgoing(outgoing -> {
							outgoing.artifact(objects(binary), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
						});
					});
				} else if (binary instanceof CppSharedLibrary) {
					configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, libraryElementsOf(binary)));
						});
					});
				}

				if (binary instanceof ComponentWithExecutable) {
					linkTask((ComponentWithExecutable) binary).configure(task -> {
						task.getLibs().setFrom((Callable<?>) () -> {
							ArtifactView view = configurations.getByName(nativeLinkConfigurationName(binary)).getIncoming().artifactView(attributes(objects, details -> {
								details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
							}));
							return view.getArtifacts().getArtifactFiles();
						});
					});
				} else if (binary instanceof ComponentWithSharedLibrary) {
					linkTask((ComponentWithSharedLibrary) binary).configure(task -> {
						task.getLibs().setFrom((Callable<?>) () -> {
							ArtifactView view = configurations.getByName(nativeLinkConfigurationName(binary)).getIncoming().artifactView(attributes(objects, details -> {
								details.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects");
							}));
							return view.getArtifacts().getArtifactFiles();
						});
					});
				}

//				if (binary instanceof ComponentWithLinkFile || binary instanceof ComponentWithExecutable) {
//					configurations.named(nativeLinkConfigurationName(binary)).configure(configuration -> {
//						configuration.attributes(attributes -> {
//							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
//						});
//					});
//				}
//				if (binary instanceof ComponentWithRuntimeFile || binary instanceof ComponentWithExecutable) {
//					configurations.named(nativeRuntimeConfigurationName(binary)).configure(configuration -> {
//						configuration.attributes(attributes -> {
//							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
//						});
//					});
//				}

				if (binary instanceof ComponentWithRuntimeUsage && !(binary instanceof CppExecutable || binary instanceof CppTestExecutable)) {
					configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, libraryElementsOf(binary)));
						});
					});
				}
			});

			project.getComponents().withType(CppLibrary.class).configureEach(component -> {
				configurations.named(cppApiElementsConfigurationName(component)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.HEADERS_CPLUSPLUS));
					});
				});
			});
		});



		Plugins.forProject(project).whenPluginApplied(CppUnitTestPlugin.class, () -> {
//			project.getDependencies().attributesSchema(schema -> {
//				schema.attribute(Attribute.of("testable", String.class));
//			});
//			project.getDependencies().artifactTypes(types -> {
//				types.create(OBJECT_CODE_TYPE, it -> it.getAttributes().attribute(Attribute.of("testable", String.class), "no"));
//				types.create(directoryType(OBJECT_CODE_TYPE), it -> it.getAttributes().attribute(Attribute.of("testable", String.class), "no"));
//			});
//
//			project.getDependencies().registerTransform(UnexportFiles.class, spec -> {
//				spec.getFrom()
//					.attribute(Attribute.of("testable", String.class), "no")
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
//					;
//				spec.getTo()
//					.attribute(Attribute.of("testable", String.class), "yes")
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, OBJECT_CODE_TYPE)
//					;
//			});
//
//			project.getDependencies().registerTransform(UnexportFiles.class, spec -> {
//				spec.getFrom()
//					.attribute(Attribute.of("testable", String.class), "no")
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(OBJECT_CODE_TYPE))
//				;
//				spec.getTo()
//					.attribute(Attribute.of("testable", String.class), "yes")
//					.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(OBJECT_CODE_TYPE))
//				;
//			});

			project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
				bucketRegistry.consumable(CppNames.of(component).configurationName("cppTestableApiElements")).configure(configuration -> {
					configuration.setVisible(false); // do not export outside the project, it makes no sense
					configuration.extendsFrom(configurations.getByName(implementationConfigurationName(component)));
					configuration.attributes(attributes -> {
						attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.C_PLUS_PLUS_API));
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.HEADERS_CPLUSPLUS));
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
					configuration.outgoing(outgoing -> {
						final TaskProvider<Sync> allHeadersTask = tasks.register(CppNames.of(component).taskName("sync", "cppHeaders").toString(), Sync.class, task -> {
							task.from(component.getHeaderFiles());
							task.setDestinationDir(task.getProject().getLayout().getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
						});
						outgoing.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_HEADER_TYPE)));
					});
				});
				bucketRegistry.consumable(CppNames.of(component).configurationName("nativeTestableCompileElements")).configure(configuration -> {
					configuration.setVisible(false); // do not export outside the project, it makes no sense
					configuration.attributes(attributes -> {
						attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "native-compile"));
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "sources"));
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
					configuration.outgoing(outgoing -> {
						final TaskProvider<Sync> allHeadersTask = tasks.register(CppNames.of(component).taskName("sync", "cppSources").toString(), Sync.class, task -> {
							task.from(cppSourceOf(component));
							task.setDestinationDir(task.getProject().getLayout().getBuildDirectory().dir("tmp/" + task.getName()).get().getAsFile());
						});
						outgoing.artifact(allHeadersTask, spec -> spec.setType(directoryType(C_PLUS_PLUS_SOURCE_TYPE)));
					});
				});
				bucketRegistry.consumable(CppNames.of(component).configurationName("nativeTestableElements")).configure(configuration -> {
					configuration.setVisible(false); // do not export outside the project, it makes no sense
					configuration.attributes(attributes -> {
						attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, "native-compile"));
						attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
				});
			});
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				if (binary instanceof CppExecutable || binary instanceof CppSharedLibrary) {
					bucketRegistry.consumable(CppNames.of(binary).configurationName("linkTestableObjectsElements")).configure(configuration -> {
						configuration.setVisible(false); // do not export outside the project, it makes no sense
						configuration.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_LINK));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, project.provider(debuggabilityOf(binary)));
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, project.provider(optimizationOf(binary)));
							attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
							attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());

							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, (binary instanceof CppExecutable) ? "testable-objects" : LibraryElements.OBJECTS));
							attributes.attribute(Attribute.of("testable", String.class), "yes");
							attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
						});
						configuration.outgoing(outgoing -> {
							if (binary instanceof CppExecutable) {
								TaskProvider<UnexportMainSymbol> objectsTask = tasks.register(CppNames.of(binary).taskName("relocateMain", "forTesting").toString(), UnexportMainSymbol.class, task -> {
									task.getObjects().from(objectsOf(binary));
									task.getOutputDirectory().set(task.getProject().getLayout().getBuildDirectory().dir("tmp/" + task.getName()));
								});
								outgoing.artifact(objectsTask, spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
							} else {
								outgoing.artifact(objects(binary), spec -> spec.setType(directoryType(OBJECT_CODE_TYPE)));
							}
						});
					});
					bucketRegistry.consumable(CppNames.of(binary).configurationName("runtimeTestableObjectsElements")).configure(configuration -> {
						configuration.setVisible(false); // do not export outside the project, it makes no sense
						configuration.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_RUNTIME));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, project.provider(debuggabilityOf(binary)));
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, project.provider(optimizationOf(binary)));
							attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
							attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());

							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.OBJECTS));

							attributes.attribute(Attribute.of("testable", String.class), "yes");
							attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
						});
						/* nothing to export for objects */
					});
					bucketRegistry.consumable(CppNames.of(binary).configurationName("linkTestableSourcesElements")).configure(configuration -> {
						configuration.setVisible(false); // do not export outside the project, it makes no sense
						configuration.extendsFrom(configurations.getByName(implementationConfigurationName(binary)));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_LINK));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, project.provider(debuggabilityOf(binary)));
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, project.provider(optimizationOf(binary)));
							attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
							attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());

							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "sources"));
							attributes.attribute(Attribute.of("testable", String.class), "yes");
							attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
						});
						/* nothing to export for objects */
					});
					bucketRegistry.consumable(CppNames.of(binary).configurationName("runtimeTestableSourcesElements")).configure(configuration -> {
						configuration.setVisible(false); // do not export outside the project, it makes no sense
						configuration.extendsFrom(configurations.getByName(runtimeElementsConfigurationName(binary)).getExtendsFrom().toArray(new Configuration[0]));
						configuration.attributes(attributes -> {
							attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_RUNTIME));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, project.provider(debuggabilityOf(binary)));
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, project.provider(optimizationOf(binary)));
							attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.getTargetMachine().getArchitecture());
							attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.getTargetMachine().getOperatingSystemFamily());

							attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
							attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, "sources"));

							attributes.attribute(Attribute.of("testable", String.class), "yes");
							attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
						});
						/* nothing to export for objects */
					});
				}
			});

			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				configurations.named(cppCompileConfigurationName(binary)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Attribute.of("testable", String.class), "no");
					});
				});
			});
			project.getComponents().withType(CppTestExecutable.class).configureEach(testExecutable -> {
				configurations.named(cppCompileConfigurationName(testExecutable)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
				});
				configurations.named(nativeLinkConfigurationName(testExecutable)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
				});
				configurations.named(nativeRuntimeConfigurationName(testExecutable)).configure(configuration -> {
					configuration.attributes(attributes -> {
						attributes.attribute(Attribute.of("testable", String.class), "yes");
						attributes.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
					});
				});
			});







			project.getComponents().withType(CppTestExecutable.class).configureEach(testExecutable -> {
				NamedDomainObjectProvider<? extends Configuration> testedSources = bucketRegistry.resolvable(CppNames.of(testExecutable).configurationName(it -> it.prefix("testedSources")));
				testedSources.configure(configuration -> {
					configuration.extendsFrom(configurations.getByName(implementationConfigurationName(testExecutable)));
				});
				testedSources.configure(attributes(objects, details -> {
					details.attribute(Usage.USAGE_ATTRIBUTE).of("native-compile");
					details.attribute(CppBinary.OPTIMIZED_ATTRIBUTE).of(providers.provider(optimizationOf(testExecutable)));
					details.attribute(CppBinary.DEBUGGABLE_ATTRIBUTE).of(providers.provider(debuggabilityOf(testExecutable)));
					details.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE).of(testExecutable.getTargetMachine().getArchitecture());
					details.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE).of(testExecutable.getTargetMachine().getOperatingSystemFamily());

					details.attribute(Attribute.of("testable", String.class), "yes");
					details.attribute(Attribute.of("internal-local-to-project-name", String.class), buildTreePathOf(project));
				}));
				cppSourceOf(testExecutable).mut(objects.fileCollection().from((Callable<?>) () -> {
					return testedSources.map(it -> {
						ArtifactView view = it.getIncoming().artifactView(spec -> {
							spec.attributes(attributes -> {
								attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, directoryType(C_PLUS_PLUS_SOURCE_TYPE));
							});
							spec.lenient(true);
						});
						return view.getArtifacts().getArtifactFiles();
					}).get().getAsFileTree();
				})::plus);
			});
		});
	}

	private static String buildTreePathOf(Project project) {
		if (GradleVersion.current().compareTo(GradleVersion.version("8.3")) >= 0) {
			return project.getBuildTreePath();
		}
		return project.getPath();
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
