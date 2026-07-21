package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.aFileBaseNamed;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.hasDescendants;
import static dev.nokee.companion.CompilationOutputs.noneRecompiled;
import static dev.nokee.companion.CompilationOutputs.recompiledFiles;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public interface AbstractNativeLanguageIncrementalCompilationAfterFailureFunctionalTester {
	@Test
	default void keepsCompiledObjectsOnFailedNonIncrementalBuild(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-failing-source-files") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		build.getBuildFile().append(groovyDsl("""
			tasks.withType(CppCompile).configureEach {
				options.incrementalAfterFailure = true
			}
		"""));
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");
		BuildResult result = null;

		result = runner.withArguments(args.withTasks(taskUnderTest).toList()).buildAndFail();
		assertThat("no previous builds, everything is out-of-date", result, taskFailed(taskUnderTest));
		assertThat(tasksOutput(result).task(taskUnderTest), taskPerformsFullRebuild());
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
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create();
		BuildResult result = null;

		result = runner.withArguments(args.withTasks(taskUnderTest).toList()).build();
		assertThat("no previous builds, everything is out-of-date", result, taskExecutedAndNotSkipped(taskUnderTest));

		CompilationOutputs outputs = CompilationOutputs.from(build.dir("build/objs")).withExtensions("o", "obj");
		CompilationOutputs.Result<BuildResult> snap = null;
		result = (snap = outputs.snapshot(() -> runner.withArguments(args.withTasks(taskUnderTest).toList()).build())).get();
		assertThat("no change, everything is up-to-date", result, taskExecutedAndUpToDate(taskUnderTest));

		// TODO: Use incremental elements
		Files.write(build.file("src/main/cpp/main.cpp"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);
		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("broken!", "", ""));
		result = runner.withArguments(args.append("-i").withTasks(taskUnderTest).toList()).buildAndFail();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest));
		assertThat(snap, noneRecompiled());

		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("int foo() { return 52; }"));
		result = runner.withArguments(args.append("-i").withTasks(taskUnderTest).toList()).build();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));
		assertThat(snap, recompiledFiles(aFileBaseNamed("main"), aFileBaseNamed("broken")));
	}
}
