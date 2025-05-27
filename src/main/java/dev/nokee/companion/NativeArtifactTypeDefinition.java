package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppExecutable;
import org.gradle.language.cpp.CppStaticLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithLinkFile;
import org.gradle.language.nativeplatform.ComponentWithRuntimeFile;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.util.Arrays;

import static dev.nokee.commons.names.CppNames.linkElementsConfigurationName;
import static dev.nokee.commons.names.CppNames.runtimeElementsConfigurationName;

public class NativeArtifactTypeDefinition {
	// The following are purposefully the file name extension to ensure a semi-stable artifact type
	//   The goal is to battle against appending the version at the end of the binary file, i.e. libfoo.so.1.2.
	//   In those cases, Gradle will use the digit of the version as the artifact type which is wrong.
	public static final String DYLIB_TYPE = "dylib";
	public static final String A_TYPE = "a";
	public static final String SO_TYPE = "so";
	public static final String EXE_TYPE = "exe";
	public static final String DLL_TYPE = "dll";
	public static final String LIB_TYPE = "lib";

	// The following are good enough artifact type to represent an often no-extension file name.
	public static final String ELF_EXECUTABLE_TYPE = "elf-executable";
	public static final String MACH_O_EXECUTABLE_TYPE = "mach-o-executable";

	// The following are type borrowed from Apple's UTI.
	public static final String OBJECT_CODE_TYPE = "public.object-code";
	public static final String C_PLUS_PLUS_SOURCE_TYPE = "public.c-plus-plus-source";
	public static final String C_PLUS_PLUS_HEADER_TYPE = "public.c-plus-plus-header";

	public static final String[] LINKABLE_TYPES = new String[] { DYLIB_TYPE, A_TYPE, SO_TYPE, LIB_TYPE, OBJECT_CODE_TYPE };
	public static final String[] RUNNABLE_TYPES = new String[] { DYLIB_TYPE, SO_TYPE, DLL_TYPE, ELF_EXECUTABLE_TYPE, MACH_O_EXECUTABLE_TYPE };

	public static String directoryType(String fileType) {
		return fileType + "-directory";
	}

	public static boolean isDirectoryCompatibleType(@Nullable String artifactType) {
		return artifactType != null && artifactType.endsWith("-directory");
	}

	public static boolean isObjectsCompatibleType(@Nullable String artifactType) {
		return artifactType != null && artifactType.endsWith("-objects");
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private final ConfigurationContainer configurations;

		@Inject
		public Rule(ConfigurationContainer configurations) {
			this.configurations = configurations;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getDependencies().getAttributesSchema().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).getCompatibilityRules().add(LibElemCompatEx.class);

				project.getComponents().withType(ProductionCppComponent.class).configureEach(component -> {
					component.getBinaries().configureEach(CppBinary.class, binary -> {
						if (binary instanceof ComponentWithLinkFile) {
							configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
										if (binary.getTargetMachine().getOperatingSystemFamily().isMacOs()) {
											artifact.setType(binary instanceof CppStaticLibrary ? "a" : "dylib");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isLinux()) {
											artifact.setType(binary instanceof CppStaticLibrary ? "a" : "so");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isWindows()) {
											artifact.setType(binary instanceof CppStaticLibrary ? "lib" : "dll");
										}
									});
								});
							});
						}

						if (binary instanceof ComponentWithRuntimeFile) {
							configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
										if (binary.getTargetMachine().getOperatingSystemFamily().isMacOs()) {
											artifact.setType(binary instanceof CppExecutable ? "mach-o-executable" : "dylib");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isLinux()) {
											artifact.setType(binary instanceof CppExecutable ? "elf-executable" : "so");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isWindows()) {
											artifact.setType(binary instanceof CppExecutable ? "exe" : "dll");
										}
									});
								});
							});
						}

						if (binary instanceof ComponentWithExecutable) {
							configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
								configuration.outgoing(outgoing -> {
									outgoing.getArtifacts().withType(ConfigurablePublishArtifact.class).configureEach(artifact -> {
										if (binary.getTargetMachine().getOperatingSystemFamily().isMacOs()) {
											artifact.setType(binary instanceof CppExecutable ? "mach-o-executable" : "dylib");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isLinux()) {
											artifact.setType(binary instanceof CppExecutable ? "elf-executable" : "so");
										} else if (binary.getTargetMachine().getOperatingSystemFamily().isWindows()) {
											artifact.setType(binary instanceof CppExecutable ? "exe" : "dll");
										}
									});
								});
							});
						}
					});
				});
			});
		}

		/*private*/ static abstract class LibElemCompatEx implements AttributeCompatibilityRule<String> {
			@Inject
			public LibElemCompatEx() {}

			@Override
			public void execute(CompatibilityCheckDetails<String> details) {
				if (details.getConsumerValue() != null && details.getConsumerValue().equals(ArtifactTypeDefinition.DIRECTORY_TYPE)) {
					if (isDirectoryCompatibleType(details.getProducerValue())) {
						details.compatible();
					}
				}
			}
		}
	}
}
