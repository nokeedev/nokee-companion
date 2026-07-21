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

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.fixtures.GradleTaskUnderTestExtension;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.companion.fixtures.GradleTestKitMatchers.ExecutedBuild;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.*;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageIncrementalCompilationFunctionalTester {
	@Test
	default void skipCompileTaskWhenNoSource(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-without-source") GradleBuildElement project) {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		ExecutedBuild result = succeeds(runner.withArguments(GradleRunnerArguments.create().withTasks(taskUnderTest).toList()));

		assertThat(result.task(taskUnderTest), noSource());
	}

	@Test
	default void executesCompileTaskWhenSource(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create();
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest), executed());

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat("no change, everything is up-to-date", result.task(taskUnderTest), upToDate());

		// TODO: Use incremental elements
		Files.write(build.file("src/main/cpp/main.cpp"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);

		result = succeeds(runner.withArguments(args.append("-i").withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
	}

	@Test
	default void removesStaleObjectFilesForRemovedSourceFiles(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-removable-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create();
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat("no previous builds, everything is out-of-date", result.task(taskUnderTest), executed());

		// TODO: Use incremental elements
		Files.delete(build.getLocation().resolve("src/main/cpp/file-to-remove.cpp"));
		// TODO: Use TaskUnderTest model
		assertThat(build.getLocation().resolve("build/objs"), aFile(hasDescendants(hasItem(withRelativePath(endsWith("file-to-remove.o"))))));

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));

		// TODO: Use TaskUnderTest model
		assertThat(build.getLocation().resolve("build/objs"), aFile(not(hasDescendants(hasItem(withRelativePath(endsWith("file-to-remove.o")))))));
	}

	@Test // https://github.com/gradle/gradle/issues/29492
	default void recompileWhenOnlyRelativePathOfSourceFileChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create();
		ExecutedBuild result;

		// TODO: write file to source of project
		Path fileToRelocate = build.file("src/main/cpp/relocate.cpp");

		runner = runner.withArguments(args.withTasks(taskUnderTest).toList());

		result = succeeds(runner);
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner);
		assertThat(result.task(taskUnderTest), skipped());

		// TODO: must be relative
		Files.move(fileToRelocate, build.dir("src/main/cpp/subdir").resolve("relocate.cpp"));
		result = succeeds(runner);
		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
	}

	@Test
	default void recompileWhenDebuggableChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner);
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.debuggable = !options.debuggable.get()
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}

	@Test
	default void recompileWhenOptimizedChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.optimized = !options.optimized.get()
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}

	@Test
	default void recompileWhenPositionIndependentChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner);
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.positionIndependentCode = !options.positionIndependentCode.get()
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}

	@Test
	default void recompileWhenCompilerArgsChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.compilerArgs.add('-DSOME_CHANGE_TO_ARGS')
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}

	@Test
	default void recompileWhenMacrosChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.preprocessorOptions.define('MY_NEW_MACRO', 'value')
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}

	@Test
	default void recompileWhenCompilerArgProviderChanges(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest);
		ExecutedBuild result;

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), executed());

		result = succeeds(runner.withArguments(args.toList()));
		assertThat(result.task(taskUnderTest), skipped());

		build.getBuildFile().append(groovyDsl("""
			subject.configure {
				options.compilerArgumentProviders.add(new CommandLineArgumentProvider() {
					Iterable<String> asArguments() {
						return ['-DSOME_CHANGE_TO_ARGS']
					}
				})
			}
		""".stripIndent()));

		result = succeeds(runner.withArguments(args.append("-i").toList()));
		assertThat(result.task(taskUnderTest), performsFullRebuild());
	}
}
