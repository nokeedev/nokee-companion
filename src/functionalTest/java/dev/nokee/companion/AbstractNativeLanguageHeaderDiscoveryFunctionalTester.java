package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.gradleplugins.runnerkit.TaskOutcome;
import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.fixtures.GradleTaskUnderTestExtension;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.companion.fixtures.GradleRunnerKitMatchers.performFullRebuildForIncrementalTask;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageHeaderDiscoveryFunctionalTester {
	@Test
	default void canDiscoverHeaderFromDefinedMacros(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.write(build.file("src/main/headers/my-include-macro.h"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), not(performFullRebuildForIncrementalTask()));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("Found all include files for ':compile'"));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroInclude(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), not(performFullRebuildForIncrementalTask()));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/a.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/b.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), not(containsString("/src/main/cpp/c.cpp")));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeWithDuplicatedHeaders(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152-ex") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), not(performFullRebuildForIncrementalTask()));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/a.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/b.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), not(containsString("/src/main/cpp/c.cpp")));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeWithDuplicatedHeadersHavingDifferentResolvingPath(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152-ex2") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.write(build.file("src/main/headers/f.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), not(performFullRebuildForIncrementalTask()));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/a.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/b.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), not(containsString("/src/main/cpp/c.cpp")));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeInvalidData(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.fix-for-gradle-34152.enabled=false");

		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.fix-for-gradle-34152.enabled=true");

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), performFullRebuildForIncrementalTask());
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/a.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/b.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/c.cpp"));

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), not(performFullRebuildForIncrementalTask()));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/a.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/b.cpp"));
		assertThat(result.task(taskUnderTest.toString()).getOutput(), not(containsString("/src/main/cpp/c.cpp")));
	}

	@Test
	default void discoverDifferentMacroInclude(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);

		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		Files.writeString(build.file("src/main/headers/my-other-include-macro.h"), """
			#ifndef _MY_OTHER_INCLUDE_MACRO_H_
			#define _MY_OTHER_INCLUDE_MACRO_H_
			#define RETURN_VALUE 52
			int foo();
			int other_foo();
			#endif
			""".stripIndent());

		result = runner.withTasks(taskUnderTest.toString()).withArgument("-Pinclude-file=my-other-include-macro.h").build();
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		assertThat(result.task(taskUnderTest.toString()), performFullRebuildForIncrementalTask());
		assertThat(result.task(taskUnderTest.toString()).getOutput(), containsString("/src/main/cpp/source-with-include-macros.cpp"));
	}
}
