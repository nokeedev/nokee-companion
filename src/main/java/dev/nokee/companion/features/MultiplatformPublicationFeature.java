package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.publishing.multiplatform.ForMultiplatformClosure;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.gradle.api.*;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.component.*;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.language.nativeplatform.ComponentWithRuntimeUsage;
import org.gradle.language.nativeplatform.internal.PublicationAwareComponent;
import org.gradle.language.plugins.NativeBasePlugin;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.nokee.commons.gradle.ActionUtils.doNothing;
import static dev.nokee.commons.gradle.ActionUtils.ignored;
import static dev.nokee.commons.gradle.SpecUtils.named;
import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.commons.names.ElementName.componentName;
import static dev.nokee.commons.names.PublishingTaskNames.*;

/*private*/ abstract /*final*/ class MultiplatformPublicationFeature implements Plugin<Project> {
	@Inject
	public MultiplatformPublicationFeature() {}

	@Override
	public void apply(Project project) {
		final Plugins<Project> plugins = Plugins.forProject(project);

		// Ensure user applies a native plugin
		plugins.whenPluginApplied(NativeBasePlugin.class, () -> {
			plugins.apply("dev.nokee.multiplatform-publishing");
			plugins.apply(DisableGradleCoreCppPublicationsRule.class);
			plugins.apply(FixCppApiElementsForPublishingRule.class);
			plugins.apply(RegisterCppPublishableComponentsRule.class);

			plugins.whenPluginApplied("maven-publish", () -> {
				project.getExtensions().getByType(PublishingExtension.class).publications(ForMultiplatformClosure.forProject(project).call("cpp", MavenPublication.class, publication -> {
					publication.bridgePublication(bridgePublication -> {
						// Configure dummy variant on application's adhoc component
						plugins.whenPluginApplied("cpp-application", () -> {
							project.getComponents().named(publication.getName(), AdhocComponentWithVariants.class).configure(component -> {
								component.addVariantsFromConfiguration(project.getConfigurations().getByName("cppCanaryElements"), doNothing());
							});
						});

						bridgePublication.from(project.getComponents().getByName(publication.getName()));
						((MavenPublicationInternal) bridgePublication).publishWithOriginalFileName();
					});

					// Fill the platform names to support non-buildable variants
					Set<? extends SoftwareComponent> variants = project.getComponents().withType(PublicationAwareComponent.class).getByName("main").getMainPublication().getVariants();
					if (variants instanceof DomainObjectSet) {
						publication.getPlatformNames().empty();
						((DomainObjectSet<? extends SoftwareComponent>) variants).all(it -> {
							publication.getPlatformNames().add(((ComponentWithCoordinates) it).getCoordinates().getName());
						});
					} else {
						publication.getPlatformNames().set(variants.stream().map(it -> ((ComponentWithCoordinates) it).getCoordinates().getName()).collect(Collectors.toList()));
					}

					// Register platform publications
					project.getComponents().matching(it -> !it.getName().equals(publication.getName()) && it.getName().startsWith(publication.getName())).all(component -> {
						publication.getPlatformPublications().register(StringGroovyMethods.uncapitalize(component.getName().substring(publication.getName().length())), platformPublication -> {
							platformPublication.from(component);
							((MavenPublicationInternal) platformPublication).publishWithOriginalFileName();
						});
					});
				}));
			});
		});
	}

	/*private*/ abstract static /*final*/ class FixCppApiElementsForPublishingRule implements Plugin<Project> {
		private final ConfigurationContainer configurations;
		private final TaskContainer tasks;
		private final ProjectLayout layout;

		@Inject
		public FixCppApiElementsForPublishingRule(ConfigurationContainer configurations, TaskContainer tasks, ProjectLayout layout) {
			this.configurations = configurations;
			this.tasks = tasks;
			this.layout = layout;
		}

		@Override
		public void apply(Project project) {
			final Plugins<Project> plugins = Plugins.forProject(project);

			plugins.whenPluginApplied(PublishingPlugin.class, () -> {
				plugins.whenPluginApplied("cpp-library", () -> {
					project.getExtensions().configure("library", (CppLibrary component) -> {
						project.afterEvaluate(ignored(() -> {
							configurations.getByName("cppApiElements").outgoing(outgoing -> {
								outgoing.variants(variants -> {
									// We need to register the compressed header variants
									//   because `cpp-library` doesn't do it.
									variants.create("compress-headers", it -> {
										// We have to add artifactType attribute to variant
										//   because `cpp-library` plugin wrongly include the attribute explicitly.
										it.getAttributes().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE);

										// When using `ivy-publish` plugin, it must be applied before `maven-publish`
										//   because of a limitation in `cpp-library` plugin.
										if (!tasks.getNames().contains("cppHeaders")) {
											// Copied from `CppLibraryPlugin`
											tasks.register("cppHeaders", Zip.class, task -> {
												task.from(component.getPublicHeaderFiles());
												task.getDestinationDirectory().set(layout.getBuildDirectory().dir("headers"));
												task.getArchiveClassifier().set("cpp-api-headers");
												task.getArchiveFileName().set("cpp-api-headers.zip");
											});
										}

										// In theory, we should set the `artifactType` during the artifact registration
										it.artifact(tasks.named("cppHeaders"), t -> t.setType("zip"));
									});
								});
							});
						}));
					});
				});
			});
		}
	}

	/*private*/ abstract static /*final*/ class DisableGradleCoreCppPublicationsRule implements Plugin<Project> {
		private final TaskContainer tasks;

		@Inject
		public DisableGradleCoreCppPublicationsRule(TaskContainer tasks) {
			this.tasks = tasks;
		}

		@Override
		public void apply(Project project) {
			final Plugins<Project> plugins = Plugins.forProject(project);

			plugins.whenPluginApplied("maven-publish", () -> {
				plugins.whenPluginApplied(CppBasePlugin.class, () -> {
					project.getExtensions().configure("publishing", (PublishingExtension publishing) -> {
						publishing.getPublications().withType(MavenPublication.class).configureEach(cpp(project, publication -> {
							tasks.withType(PublishToMavenLocal.class).configureEach(named(publishPublicationToAnyRepositories(publication)).whenSatisfied(disabled()));
							tasks.withType(PublishToMavenRepository.class).configureEach(named(publishPublicationToAnyRepositories(publication)).whenSatisfied(disabled()));
							tasks.withType(GenerateMavenPom.class).configureEach(named(generatePomFileTaskName(publication)::equals).whenSatisfied(disabled()));
							tasks.withType(GenerateModuleMetadata.class).configureEach(named(generateMetadataFileTaskName(publication)::equals).whenSatisfied(disabled()));
						}));
					});
				});
			});
		}

		private static Action<Task> disabled() {
			return task -> {
				task.setEnabled(false); // disabled
				task.setGroup(null); // hide existence of this task
				task.setDescription(task.getDescription() + " (disabled by multiplatform-publishing plugin)");
				task.doFirst("disable task", new Action<Task>() {
					@Override
					public void execute(Task task) {
						throw new RuntimeException();
					}
				});
			};
		}

		private static Action<MavenPublication> cpp(Project project, Action<? super MavenPublication> action) {
			return publication -> {
				@Nullable
				final SoftwareComponent component = project.getComponents().findByName(publication.getName());
				if (component instanceof CppComponent || component instanceof CppBinary) {
					action.execute(publication);
				}
			};
		}
	}


	/*private*/ static abstract /*final*/ class RegisterCppPublishableComponentsRule implements Plugin<Project> {
		private final SoftwareComponentFactory factory;

		@Inject
		public RegisterCppPublishableComponentsRule(SoftwareComponentFactory factory) {
			this.factory = factory;
		}

		@Override
		public void apply(Project project) {
			final Plugins<Project> plugins = Plugins.forProject(project);

			plugins.whenPluginApplied(PublishingPlugin.class, () -> {
				plugins.whenPluginApplied("cpp-application", () -> {
					project.getExtensions().configure("application", registerPublishableComponents(project));
				});
				plugins.whenPluginApplied("cpp-library", () -> {
					project.getExtensions().configure("library", registerPublishableComponents(project));
				});
			});
		}

		private Action<ProductionCppComponent> registerPublishableComponents(Project project) {
			return component -> {
				final AdhocComponentWithVariants publishableComponent = factory.adhoc(componentName("cpp").qualifiedBy(qualifyingName(component)).toString());

				if (component instanceof CppLibrary) {
					publishableComponent.addVariantsFromConfiguration(project.getConfigurations().getByName(cppApiElementsConfigurationName(component)), details -> {
						if (details.getConfigurationVariant().getArtifacts().stream().anyMatch(it -> it.getType().isEmpty() || it.getType().equals(ArtifactTypeDefinition.DIRECTORY_TYPE))) {
							details.skip();
						}
					});
				}

				project.getComponents().add(publishableComponent);

				component.getBinaries().configureEach(CppBinary.class, binary -> {
					final AdhocComponentWithVariants publishableVariantComponent = factory.adhoc(componentName("cpp").qualifiedBy(qualifyingName(binary)).toString());

					if (binary instanceof ComponentWithLinkUsage) {
						publishableVariantComponent.addVariantsFromConfiguration(project.getConfigurations().getByName(linkElementsConfigurationName(binary)), includeAll());
					}

					if (binary instanceof ComponentWithRuntimeUsage) {
						publishableVariantComponent.addVariantsFromConfiguration(project.getConfigurations().getByName(runtimeElementsConfigurationName(binary)), includeAll());
					}

					project.getComponents().add(publishableVariantComponent);
				});
			};
		}


		private static Action<ConfigurationVariantDetails> includeAll() {
			return __ -> {};
		}
	}
}
