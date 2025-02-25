/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import static dev.nokee.commons.fixtures.BuildResultExMatchers.*;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageIncrementalCompilationFunctionalTester {
	@Test
	default void skipCompileTaskWhenNoSource(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-without-source") GradleBuildElement project) {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = runner.withTasks(taskUnderTest.toString()).build();

		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.NO_SOURCE));
	}

	@Test
	default void executesCompileTaskWhenSource(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat("no change, everything is up-to-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.UP_TO_DATE));

		// TODO: Use incremental elements
		Files.write(build.file("src/main/cpp/main.cpp"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);

		result = runner.withArgument("-i").withTasks(taskUnderTest.toString()).build();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));
	}

	@Test
	default void removesStaleObjectFilesForRemovedSourceFiles(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-removable-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));
		assertThat(result.task(taskUnderTest.toString()).getOutcome(), equalTo(TaskOutcome.SUCCESS));

		// TODO: Use incremental elements
		Files.delete(build.getLocation().resolve("src/main/cpp/file-to-remove.cpp"));
		// TODO: Use TaskUnderTest model
		assertThat(build.getLocation().resolve("build/objs"), aFile(hasDescendants(hasItem(withRelativePath(endsWith("file-to-remove.o"))))));

		result = runner.withTasks(taskUnderTest.toString()).build();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));

		// TODO: Use TaskUnderTest model
		assertThat(build.getLocation().resolve("build/objs"), aFile(not(hasDescendants(hasItem(withRelativePath(endsWith("file-to-remove.o")))))));
	}

	@Test // https://github.com/gradle/gradle/issues/29492
	default void recompileWhenOnlyRelativePathOfSourceFileChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;

		// TODO: write file to source of project
		Path fileToRelocate = build.file("src/main/cpp/relocate.cpp");

		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		// TODO: must be relative
		Files.move(fileToRelocate, build.dir("src/main/cpp/subdir").resolve("relocate.cpp"));
		result = runner.build();
		assertThat(result, taskPerformsIncrementalBuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenDebuggableChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.debuggable = !options.debuggable.get()
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenOptimizedChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.optimized = !options.optimized.get()
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenPositionIndependentChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.positionIndependentCode = !options.positionIndependentCode.get()
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenCompilerArgsChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.compilerArgs.add('-DSOME_CHANGE_TO_ARGS')
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenMacrosChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.preprocessorOptions.definedMacros.put('MY_NEW_MACRO', 'value')
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}

	@Test
	default void recompileWhenCompilerArgProviderChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		BuildResult result = null;
		runner = runner.withTasks(taskUnderTest.toString());

		result = runner.build();
		assertThat(result, taskExecuted(taskUnderTest.toString()));

		result = runner.build();
		assertThat(result, taskSkipped(taskUnderTest.toString()));

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.compilerArgumentProviders.add(new CommandLineArgumentProvider() {
					Iterable<String> asArguments() {
						return ['-DSOME_CHANGE_TO_ARGS']
					}
				})
			}
		""".stripIndent()));

		result = runner.withArgument("-i").build();
		assertThat(result, taskPerformsFullRebuild(taskUnderTest.toString()));
	}
}
