package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.fixtures.GradleTaskUnderTestExtension;
import dev.nokee.commons.sources.GradleBuildElement;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageCompilationFunctionalTester {
	@Test
//	@MethodSource("AbstractNativeLanguageCompilationFunctionalTester#foo")
//	@ValueSource(strings = {"compile"})
	default void buildFailsWhenCompilationFails(/*/task to execute/ String taskUnderTest, String expectedCompilationFailureCause,*/ @TempDir Path testDirectory, @GradleProject("project-with-failing-source-files") GradleBuildElement project) {
		String taskUnderTest = ":compile";
		String expectedCompilationFailureCause = "C++ compiler failed while compiling broken.cpp";

		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		assertThat(fails(runner.withArguments(taskUnderTest)), allOf(
			hasFailureDescription("Execution failed for task '" + taskUnderTest + "'."),
			hasFailureCause(containsString(expectedCompilationFailureCause))));
	}
}
