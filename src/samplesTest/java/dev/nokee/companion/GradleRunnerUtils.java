package dev.nokee.companion;

import dev.gradleplugins.fixtures.sources.ProjectElement;
import dev.gradleplugins.runnerkit.GradleExecutor;
import dev.gradleplugins.runnerkit.GradleRunner;

public class GradleRunnerUtils {
	public static GradleRunner from(ProjectElement project) {
		String gradleVersion = GradleWrapper.wrapperProperties(project.file("gradle/wrapper/gradle-wrapper.properties")).getGradleVersion();
		return GradleRunner.create(GradleExecutor.gradleTestKit()).inDirectory(project.getLocation().toFile()).withGradleVersion(gradleVersion);
	}
}
