package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.platform.jni.fixtures.elements.CppGreeter;
import dev.nokee.platform.nativebase.fixtures.CppGreeterApp;
import dev.nokee.platform.nativebase.fixtures.CppGreeterTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

class CppUnitTestOnlyFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
		});
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);
	}

	@Test
	void canReselectTestedBinaryToOptimizedVariant() {
		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":compileTestCpp", ":relocateMainForTest", ":linkTest", ":runTest"));
	}
}
