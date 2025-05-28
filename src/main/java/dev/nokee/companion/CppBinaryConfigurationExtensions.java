package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.reflect.TypeOf;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.language.nativeplatform.ComponentWithRuntimeUsage;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

/**
 * Represents binary task extensions to avoid realizing the compile, link, create and install tasks.
 */
public final class CppBinaryConfigurationExtensions {
	/**
	 * {@return a configurable provider to the link elements configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static NamedDomainObjectProvider<Configuration> linkElementsOf(ComponentWithLinkUsage binary) {
		return (NamedDomainObjectProvider<Configuration>) ((ExtensionAware) binary).getExtensions().getByName("linkElements");
	}

	/**
	 * {@return a configurable provider to the runtime elements configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static NamedDomainObjectProvider<Configuration> runtimeElementsOf(ComponentWithRuntimeUsage binary) {
		return (NamedDomainObjectProvider<Configuration>) ((ExtensionAware) binary).getExtensions().getByName("runtimeElements");
	}

	/**
	 * {@return a configurable provider to the cpp compile configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked"})
	public static NamedDomainObjectProvider<Configuration> cppCompileOf(CppBinary binary) {
		return (NamedDomainObjectProvider<Configuration>) ((ExtensionAware) binary).getExtensions().getByName("cppCompile");
	}

	/**
	 * {@return a configurable provider to the native link configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked"})
	public static NamedDomainObjectProvider<Configuration> nativeLinkOf(CppBinary binary) {
		return (NamedDomainObjectProvider<Configuration>) ((ExtensionAware) binary).getExtensions().getByName("nativeLink");
	}

	/**
	 * {@return a configurable provider to the native runtime configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked"})
	public static NamedDomainObjectProvider<Configuration> nativeRuntimeOf(CppBinary binary) {
		return (NamedDomainObjectProvider<Configuration>) ((ExtensionAware) binary).getExtensions().getByName("nativeRuntime");
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private static final TypeOf<NamedDomainObjectProvider<Configuration>> CONFIGURATION_PROVIDER_TYPE = new TypeOf<NamedDomainObjectProvider<Configuration>>() {};
		private final ConfigurationContainer configurations;

		@Inject
		public Rule(ConfigurationContainer configurations) {
			this.configurations = configurations;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				// Must configure through project binaries to ensure correct configuration ordering.
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					((ExtensionAware) binary).getExtensions().add(CONFIGURATION_PROVIDER_TYPE, "cppCompile", configurations.named(cppCompileConfigurationName(binary)));
					((ExtensionAware) binary).getExtensions().add(CONFIGURATION_PROVIDER_TYPE, "nativeLink", configurations.named(nativeLinkConfigurationName(binary)));
					((ExtensionAware) binary).getExtensions().add(CONFIGURATION_PROVIDER_TYPE, "nativeRuntime", configurations.named(nativeRuntimeConfigurationName(binary)));

					if (binary instanceof ComponentWithLinkUsage) {
						((ExtensionAware) binary).getExtensions().add(CONFIGURATION_PROVIDER_TYPE, "linkElements", configurations.named(linkElementsConfigurationName(binary)));
					}

					if (binary instanceof ComponentWithRuntimeUsage) {
						((ExtensionAware) binary).getExtensions().add(CONFIGURATION_PROVIDER_TYPE, "runtimeElements", configurations.named(runtimeElementsConfigurationName(binary)));
					}
				});
			});
		}
	}
}
