package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.gradleplugins.runnerkit.TaskOutcome;
import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.hamcrest.gradle.FileSystemMatchers;
import dev.nokee.commons.sources.GradleBuildElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.commons.fixtures.BuildResultExMatchers.taskPerformsFullRebuild;
import static dev.nokee.commons.fixtures.BuildResultExMatchers.taskPerformsIncrementalBuild;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.*;
import static dev.nokee.companion.CompilationOutputs.noneRecompiled;
import static dev.nokee.companion.CompilationOutputs.recompiledFiles;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public interface AbstractNativeLanguageIncrementalCompilationAfterFailureFunctionalTester {
	@Test
	default void keepsCompiledObjectsOnFailedNonIncrementalBuild(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-failing-source-files") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		build.getBuildFile().append(groovyDsl("""
			tasks.withType(CppCompile).configureEach {
				options.incrementalAfterFailure = true
			}
		"""));
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withArgument("-i");
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).buildAndFail();
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.FAILED));
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
		assertThat(build.dir("build/objs"), hasDescendants(aFileBaseNamed("message"), aFileBaseNamed("split"), aFileBaseNamed("destructor"), aFileBaseNamed("remove"), aFileBaseNamed("join"), aFileBaseNamed("add"), aFileBaseNamed("get"), aFileBaseNamed("main"), aFileBaseNamed("copy_ctor_assign"), aFileBaseNamed("size")));
	}

	@Test
	default void incrementalAfterFailure(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		build.getBuildFile().append(groovyDsl("""
			tasks.withType(CppCompile).configureEach {
				options.incrementalAfterFailure = true
			}
		"""));
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		CompilationOutputs outputs = CompilationOutputs.from(build.dir("build/objs")).withExtensions("o", "obj");
		CompilationOutputs.Result<BuildResult> snap = null;
		result = (snap = outputs.snapshot(() -> runner.withTasks(taskUnderTest.toString()).build())).get();
		assertThat("no change, everything is up-to-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		// TODO: Use incremental elements
		Files.write(build.file("src/main/cpp/main.cpp"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);
		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("broken!", "", ""));
		result = runner.withArgument("-i").withTasks(taskUnderTest.toString()).buildAndFail();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));
		assertThat(snap, noneRecompiled());

		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("int foo() { return 52; }"));
		result = runner.withArgument("-i").withTasks(taskUnderTest.toString()).build();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));
		assertThat(snap, recompiledFiles(aFileBaseNamed("main"), aFileBaseNamed("broken")));
	}
}
