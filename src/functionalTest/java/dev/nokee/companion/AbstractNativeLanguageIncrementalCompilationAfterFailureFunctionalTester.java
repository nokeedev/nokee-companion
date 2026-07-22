package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
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
import static org.hamcrest.Matchers.not;

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
		GradleRunnerArguments args = GradleRunnerArguments.create().withInfoLogging();

		ExecutedBuild result = fails(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest), failed());
		assertThat(result.task(taskUnderTest), performsFullRebuild());
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
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest), executed());

		CompilationOutputs outputs = CompilationOutputs.from(build.dir("build/objs")).withExtensions("o", "obj");
		CompilationOutputs.Result<ExecutedBuild> snap = null;
		result = (snap = outputs.snapshot(() -> succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList())))).get();
		assertThat("no change, everything is up-to-date", result.task(taskUnderTest), upToDate());

		// TODO: Use incremental elements
		Files.write(build.file("src/main/cpp/main.cpp"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);
		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("broken!", "", ""));
		result = fails(runner.withArguments(args.withInfoLogging().withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(snap, noneRecompiled());

		Files.write(build.file("src/main/cpp/broken.cpp"), Arrays.asList("int foo() { return 52; }"));
		result = succeeds(runner.withArguments(args.withInfoLogging().withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(snap, recompiledFiles(aFileBaseNamed("main"), aFileBaseNamed("broken")));
	}
}
