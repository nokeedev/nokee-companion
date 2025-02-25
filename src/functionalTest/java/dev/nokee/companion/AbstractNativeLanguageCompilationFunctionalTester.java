package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.fixtures.GradleTaskUnderTestExtension;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureCause;
import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureDescription;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
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
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = runner.withTasks(taskUnderTest).buildAndFail();

		assertThat(result, hasFailureDescription("Execution failed for task '" + taskUnderTest + "'."));
		assertThat(result, hasFailureCause("A build operation failed."));
		assertThat(result, hasFailureCause(containsString(expectedCompilationFailureCause)));
	}
}
