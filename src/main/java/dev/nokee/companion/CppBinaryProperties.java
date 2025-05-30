package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.language.nativeplatform.ComponentWithRuntimeUsage;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

public final class CppBinaryProperties {
	/**
	 * Returns a property to configure optimization of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the optimized property
	 */
	public static ShadowProperty<Boolean> optimizationOf(CppBinary binary) {
		return ShadowProperty.of(binary, "optimized", binary::isOptimized);
	}

	/**
	 * Returns a property to configure debuggability of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the debuggable property
	 */
	public static ShadowProperty<Boolean> debuggabilityOf(CppBinary binary) {
		return ShadowProperty.of(binary, "debuggable", binary::isDebuggable);
	}

	/**
	 * Returns a property to configure compile include path of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the compile include path property
	 */
	public static ShadowProperty<FileCollection> compileIncludePathOf(CppBinary binary) {
		return ShadowProperty.of(binary, "compileIncludePath", binary::getCompileIncludePath);
	}

	/*private*/ abstract static /*final*/ class Rule implements Plugin<Project> {
		private final ConfigurationContainer configurations;
		private final TaskContainer tasks;
		private final ProviderFactory providers;

		@Inject
		public Rule(ConfigurationContainer configurations, TaskContainer tasks, ProviderFactory providers) {
			this.configurations = configurations;
			this.tasks = tasks;
			this.providers = providers;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				// Rewire optimized/debuggable to be shadow property aware
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					// Rewire the tasks
					tasks.named(compileTaskName(binary), CppCompile.class).configure(task -> {
						task.getOptions().getOptimized().set(providers.provider(optimizationOf(binary)::get));
						task.getOptions().getDebuggable().set(providers.provider(debuggabilityOf(binary)::get));
						task.getIncludes().setFrom(compileIncludePathOf(binary));
					});
					if (binary instanceof ComponentWithExecutable || binary instanceof ComponentWithSharedLibrary) {
						tasks.named(linkTaskName(binary), AbstractLinkTask.class).configure(task -> {
							task.getDebuggable().set(providers.provider(debuggabilityOf(binary)::get));
						});
					}

					// Rewire the configurations
					configurations.named(cppCompileConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(binary)::get));
						});
					});

					configurations.named(nativeLinkConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(binary)::get));
						});
					});
					configurations.named(nativeRuntimeConfigurationName(binary)).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(binary)::get));
						});
					});

					if (binary instanceof ComponentWithLinkUsage) {
						configurations.named(linkElementsConfigurationName(binary)).configure(configuration -> {
							configuration.attributes(attributes -> {
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(binary)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(binary)::get));
							});
						});
					}
					if (binary instanceof ComponentWithRuntimeUsage) {
						configurations.named(runtimeElementsConfigurationName(binary)).configure(configuration -> {
							configuration.attributes(attributes -> {
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(optimizationOf(binary)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(debuggabilityOf(binary)::get));
							});
						});
					}
				});
			});
		}
	}
}
