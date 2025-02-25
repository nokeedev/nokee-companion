package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.templates.CppApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureCause;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;

class ShadowedCppSourceFilesFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);
		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
			it.id("cpp-application");
		});
		build.getBuildFile().append(groovyDsl("""
			tasks.withType(CppCompile).configureEach {
				source(file('broken.cpp')) // could be generated task
			}
		""".stripIndent()));

		// we are using a broken file to make the assertion easier
		Files.writeString(testDirectory.resolve("broken.cpp"), "broken!");

		new CppApp().writeToProject(testDirectory);
	}

	@Test
	void keepsCppSourceAddedViaProjectConfiguration() {
		BuildResult result = runner.withTasks("compileReleaseCpp").buildAndFail();
		assertThat(result, hasFailureCause("C++ compiler failed while compiling broken.cpp."));
	}
}
