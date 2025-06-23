package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.language.nativeplatform.ComponentWithRuntimeUsage;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;

import javax.inject.Inject;

public final class CppBinaryProperties {
	/**
	 * Returns a property to configure optimization of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the optimized property
	 */
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#optimizationOf"*/)
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
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#debuggabilityOf"*/)
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
	@Deprecated(/*since = "1.0-milestone-28", forRemoval = true, replacedBy = "CppEcosystemUtilities#compileIncludePathOf"*/)
	public static ShadowProperty<FileCollection> compileIncludePathOf(CppBinary binary) {
		return ShadowProperty.of(binary, "compileIncludePath", binary::getCompileIncludePath);
	}

	/*private*/ abstract static /*final*/ class Rule implements Plugin<Project> {
		private final ProviderFactory providers;
		private final CppEcosystemUtilities access;

		@Inject
		public Rule(ProviderFactory providers, Project project) {
			this.providers = providers;
			this.access = CppEcosystemUtilities.forProject(project);
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				// Rewire optimized/debuggable to be shadow property aware
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					// Rewire the tasks
					access.compileTaskOf(binary).configure(task -> {
						task.getOptions().getOptimized().set(providers.provider(access.optimizationOf(binary)::get));
						task.getOptions().getDebuggable().set(providers.provider(access.debuggabilityOf(binary)::get));
						task.getIncludes().setFrom(access.compileIncludePathOf(binary));
					});
					if (binary instanceof ComponentWithExecutable) {
						access.linkTaskOf((ComponentWithExecutable) binary).configure(task -> {
							task.getDebuggable().set(providers.provider(access.debuggabilityOf(binary)::get));
						});
					} else if (binary instanceof ComponentWithSharedLibrary) {
						access.linkTaskOf((ComponentWithSharedLibrary) binary).configure(task -> {
							task.getDebuggable().set(providers.provider(access.debuggabilityOf(binary)::get));
						});
					}

					// Rewire the configurations
					access.cppCompileOf(binary).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
						});
					});

					access.nativeLinkOf(binary).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
						});
					});
					access.nativeRuntimeOf(binary).configure(configuration -> {
						configuration.attributes(attributes -> {
							attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
							attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
						});
					});

					if (binary instanceof ComponentWithLinkUsage) {
						access.linkElementsOf((ComponentWithLinkUsage) binary).configure(configuration -> {
							configuration.attributes(attributes -> {
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
							});
						});
					}
					if (binary instanceof ComponentWithRuntimeUsage) {
						access.runtimeElementsOf((ComponentWithRuntimeUsage) binary).configure(configuration -> {
							configuration.attributes(attributes -> {
								attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, providers.provider(access.optimizationOf(binary)::get));
								attributes.attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, providers.provider(access.debuggabilityOf(binary)::get));
							});
						});
					}
				});
			});
		}
	}
}
