package dev.nokee.companion;

import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;

import static dev.nokee.companion.ExemplarTestUtils.getDynamicTests;
import static dev.nokee.companion.GradleRunnerUtils.from;

@ExtendWith(SampleExtension.class)
@Disabled
class CppOverlinkingAvoidanceSampleTests {
	static ExemplarElement<GradleBuildElement> project;
	@TempDir static Path testDirectory;
	static GradleRunner runner;

	@BeforeAll
	static void setup(@Sample("cpp-overlinking-avoidance") ExemplarElement<GradleBuildElement> sample) {
		project = sample.writeToDirectory(testDirectory);
		runner = from(project).withPluginClasspath().forwardOutput().withRichConsoleEnabled();
	}

	@TestFactory
	Collection<DynamicTest> canExecute() {
		return getDynamicTests(project, testDirectory, runner);
	}
}
