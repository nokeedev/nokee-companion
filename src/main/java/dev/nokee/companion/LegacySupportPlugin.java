package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginAware;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;

/*public*/ abstract /*final*/ class LegacySupportPlugin<TargetType extends PluginAware & ExtensionAware> implements Plugin<TargetType> {
	@Inject
	public LegacySupportPlugin() {}

	public void apply(TargetType target) {
		if (target instanceof Project) {
			doApply((Project) target);
		} else if (target instanceof Settings) {
			doApply((Settings) target);
		}
	}

	private void doApply(Settings settings) {
		settings.getGradle().allprojects(project -> project.getPluginManager().apply("dev.nokee.native-companion"));
	}

	private void doApply(Project project) {
		final NativeCompanionExtension extension = project.getExtensions().create(NativeCompanionExtension.class, "nativeCompanion", Extension.class, project);
		final FeaturePreviews feature = project.getObjects().newInstance(FeaturePreviews.class, extension);
		final Plugins<Project> plugins = Plugins.forProject(project);

		plugins.apply("native-companion.replace-cpp-compile-task");
		feature.apply("native-task-object-files-extension");
		feature.apply("compile-tasks-extension");
		plugins.apply(CppSourceFiles.Rule.class);
		plugins.apply(CppBinaryObjects.Rule.class);
		plugins.apply(CppBinaryProperties.Rule.class);
		plugins.apply(CppUnitTestExtensions.Rule.class);
		plugins.apply(CppBinaryTaskExtensions.Rule.class);
		plugins.apply(CppBinaryConfigurationExtensions.Rule.class);
		feature.apply("binary-task-extensions");
		feature.apply("objects-lifecycle-tasks");

		feature.apply("fix-for-gradle-29492");
		feature.apply("fix-for-gradle-29744");
		feature.apply("fix-for-gradle-34152");
		feature.apply("fix-for-public-headers");
		feature.apply("fix-for-version-catalog");
		feature.apply("incremental-compilation-after-failure");
		feature.apply("overlinking-avoidance");

		feature.apply("multiplatform-publication");
		// TODO: Source include (cxx)
		// TODO: includes .i/.ii & .hh

		// TODO: Copy variant (or part of) - work around the lack of variants
		//   dup compile task
		//   dup link task
		//   dup configuration
		//   etc.
	}

	/*private*/ static abstract /*final*/ class Extension implements NativeCompanionExtension {
		private final Plugins<Project> plugins;

		@Inject
		public Extension(Project project) {
			this.plugins = Plugins.forProject(project);
		}

		@Override
		public void enableFeaturePreview(String featureName) {
			plugins.apply("native-companion.features." + featureName);
		}
	}

	/*private*/ static abstract /*final*/ class FeaturePreviews {
		private final NativeCompanionExtension extension;
		private final ProviderFactory providers;

		@Inject
		public FeaturePreviews(NativeCompanionExtension extension, ProviderFactory providers) {
			this.extension = extension;
			this.providers = providers;
		}

		public void apply(String featureName) {
			boolean enabled = providers.gradleProperty("dev.nokee.native-companion." + featureName + ".enabled")
				.orElse(providers.gradleProperty("dev.nokee.native-companion.all-features.enabled"))
				.map(Boolean::valueOf).getOrElse(false);
			if (enabled) {
				extension.enableFeaturePreview(featureName);
			}
		}
	}
}
