package dev.nokee.companion;

import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;

class AssertFeatureFlagsFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
		});

		runner = runner.withTasks("help").withArgument("-w");
	}

	@Nested
	class SystemProperties {
		@Test
		void throwsExceptionWhenUsingFeatureFlagAsSystemProperties() {
			runner.withArgument("-D" + enableFeatureFlag("any-feature-flag")).buildAndFail();
		}
	}

	@Nested
	class GradleProperties {
		@Test
		void throwsExceptionWhenUsingUnknownFeature() {
			runner.withArgument("-P" + enableFeatureFlag("unknown-feature")).buildAndFail();
		}

		@Test
		void doesNotThrowExceptionWhenUsingKnownFeature() {
			runner.withArgument("-P" + enableFeatureFlag("fix-for-gradle-29492")).build();
		}

		@Test
		void doesNotThrowExceptionWhenUsingAllFeature() {
			runner.withArgument("-P" + enableFeatureFlag("all-features")).build();
		}
	}

	static String enableFeatureFlag(String featureName) {
		return "dev.nokee.native-companion." + featureName + ".enabled=true";
	}
}
