package dev.nokee.companion;

import dev.nokee.commons.sources.GradleBuildElement;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class NokeeLegacyPluginFunctionalTest {
	GradleBuildElement build;
    @TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setUp() {
		build = GradleBuildElement.inDirectory(testDirectory);
		runner = runnerFor(build).withPluginClasspath().forwardOutput();
	}

	static GradleRunner runnerFor(GradleBuildElement build) {
		// TODO: Check if there is a wrapper
		return GradleRunner.create().withProjectDir(build.getLocation().toFile());
	}

    @Test void canRunTask() {
		build.getBuildFile().plugins(it -> it.id("dev.nokee.native-companion"));
		runner.withArguments("help").build();
    }
}
