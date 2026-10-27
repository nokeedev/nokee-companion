package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import org.gradle.testkit.runner.GradleRunner;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

class EnableFeaturePreviewFunctionalTests {
	static final String FEATURE_NAME = "fix-for-gradle-29492";
	static final String FEATURE_PLUGIN_TYPE = "FixCppCompileRelativePathSensitivityFeature";
	static final String UNKNOWN_FEATURE_NAME = "unknown-feature";

	GradleBuild build;
	GradleRunner runner;
	GradleRunnerArguments args = GradleRunnerArguments.create().withTasks("help");

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.rootProject(project -> {
			project.plugins(it -> it.id("dev.nokee.native-companion"));
			project.append(groovyDsl("""
				project.plugins.all {
					def featurePkgPrefix = 'dev.nokee.companion.features.'
					def className = it.class.name
					if (className.startsWith(featurePkgPrefix)) {
						className = className.substring(featurePkgPrefix.length())
						if (className.endsWith('_Decorated')) {
							className = className.substring(0, className.length() - '_Decorated'.length())
						}
						println "%s ${className}"
					}
				}""".formatted(MARKER)));
		});
	}

	static String flagOf(String featureName) {
		return "dev.nokee.native-companion." + featureName + ".enabled";
	}

	static String allFeatureFlag() {
		return "dev.nokee.native-companion.all-features.enabled";
	}

	//region Enabled features, as reported by the build
	private static final String MARKER = "APPLIED PLUGIN:";

	/** Matches the feature plugins applied to the build, by type, in application order. */
	private static Matcher<ExecutedBuild> enabledFeatures(Matcher<? super Iterable<String>> matcher) {
		return new FeatureMatcher<ExecutedBuild, Iterable<String>>(matcher, "a build with enabled features", "enabled features") {
			@Override
			protected Iterable<String> featureValueOf(ExecutedBuild actual) {
				return actual.output().lines().filter(it -> it.startsWith(MARKER)).map(it -> it.substring(MARKER.length()).trim()).toList();
			}
		};
	}
	//endregion

	@Nested
	class WhenEnabledViaExtension {
		@BeforeEach
		void givenEnabledFeaturePreview() {
			build.rootProject(project -> project.append(groovyDsl(String.format("nativeCompanion.enableFeaturePreview('%s')", FEATURE_NAME))));
		}

		@Test
		void appliesFeaturePlugin() {
			assertThat(succeeds(runner.withArguments(args.toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}

		@Test
		void doesNotApplyFeaturePluginWhenFeatureFlagDisabled() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(flagOf(FEATURE_NAME), false).toList())), enabledFeatures(not(hasItem(FEATURE_PLUGIN_TYPE))));
		}

		@Test
		void appliesFeaturePluginEvenWhenAllFeaturesFlagDisabled() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(allFeatureFlag(), false).toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}

		@Test
		void appliesFeaturePluginWhenAllFeaturesFlagDisabled() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(flagOf("all-features"), false).toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}
	}

	@Test
	void throwsExceptionWhenUnknownFeatureEnabledViaExtension() {
		build.rootProject(project -> project.append(groovyDsl(String.format("nativeCompanion.enableFeaturePreview('%s')", UNKNOWN_FEATURE_NAME))));
		assertThat(fails(runner.withArguments(args.toList())), hasFailureCause("The feature '" + UNKNOWN_FEATURE_NAME + "' is not known by the native-companion plugin."));
	}

	@Test
	void throwsExceptionWhenUnknownFeatureEnabledViaGradleProperty() {
		assertThat(fails(runner.withArguments(args.withGradleProperty(flagOf(UNKNOWN_FEATURE_NAME), true).toList())), hasFailureCause("The following features are not known by the native-companion plugin: unknown-feature"));
	}

	@Test
	void doesNotApplyFeaturePluginByDefault() {
		assertThat(succeeds(runner.withArguments(args.toList())), enabledFeatures(not(hasItem(FEATURE_PLUGIN_TYPE))));
	}

	@Test
	void appliesFeaturePluginWhenAllFeaturesFlagEnabled() {
		assertThat(succeeds(runner.withArguments(args.withGradleProperty(allFeatureFlag(), true).toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
	}

	@Test
	void doesNotApplyFeaturePluginWhenFeatureFlagDisabledOverAllFeaturesFlagEnabled() {
		assertThat(succeeds(runner.withArguments(args.withGradleProperty(flagOf(FEATURE_NAME), false).withGradleProperty(flagOf("all-features"), true).toList())), enabledFeatures(not(hasItem(FEATURE_PLUGIN_TYPE))));
	}

	@Nested
	class WhenEnabledViaGradleProperty {
		@BeforeEach
		void givenFeatureFlagEnabled() {
			args = args.withGradleProperty(flagOf(FEATURE_NAME), true);
		}

		@Test
		void appliesFeaturePlugin() {
			assertThat(succeeds(runner.withArguments(args.toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}

		@Test
		void doesNotApplyFeaturePluginWhenExplicitlyDisabledViaGradleProperty() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(flagOf(FEATURE_NAME), false).toList())), enabledFeatures(not(hasItem(FEATURE_PLUGIN_TYPE))));
		}

		@Test
		void appliesFeaturePluginEvenWhenAllFeaturesFlagDisabled() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(allFeatureFlag(), false).toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}

		@Test
		void appliesFeaturePluginWhenFeatureFlagEnabledOverAllFeaturesFlagDisabled() {
			assertThat(succeeds(runner.withArguments(args.withGradleProperty(flagOf("all-features"), false).toList())), enabledFeatures(hasItem(FEATURE_PLUGIN_TYPE)));
		}
	}
}
