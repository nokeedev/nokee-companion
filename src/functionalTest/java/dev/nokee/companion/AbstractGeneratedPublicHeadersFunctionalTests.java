package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.sources.GradleBuildElement;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.nokee.companion.fixtures.GradleTestKitMatchers.succeeds;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.tasksExecutedAndNotSkipped;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

@ExtendWith(GradleProjectExtension.class)
public interface AbstractGeneratedPublicHeadersFunctionalTests {
	@Test
	default void canGeneratePublicHeaders(@TempDir Path testDirectory, @GradleProject("project-with-generated-public-headers") GradleBuildElement project) {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withPluginClasspath().forwardOutput().withProjectDir(build.getLocation().toFile());

		assertThat(succeeds(runner.withArguments("verify")), tasksExecutedAndNotSkipped(hasItem(":generatePublicHeaders")));
	}
}
