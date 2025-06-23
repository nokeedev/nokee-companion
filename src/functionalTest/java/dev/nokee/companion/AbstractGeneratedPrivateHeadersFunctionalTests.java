package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.gradleplugins.runnerkit.TaskOutcome;
import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith(GradleProjectExtension.class)
public interface AbstractGeneratedPrivateHeadersFunctionalTests {
	@Test
	default void canGeneratePrivateHeaders(@TempDir Path testDirectory, @GradleProject("project-with-generated-private-headers") GradleBuildElement project) {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).withPluginClasspath().forwardOutput().withTasks("verify").inDirectory(build.getLocation());
		BuildResult result = null;

		result = runner.build();
		assertThat(result.task(":generatePrivateHeaders").getOutcome(), equalTo(TaskOutcome.SUCCESS));
	}
}
