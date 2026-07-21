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
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageCachingCompilationFunctionalTester {
	@Test
	default void restoreFromCacheInSecondLocation(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-sources") GradleBuildElement project) {
		GradleBuildElement firstBuild = project.writeToDirectory(testDirectory.resolve("first"));
		GradleBuildElement secondBuild = project.writeToDirectory(testDirectory.resolve("second"));
		GradleRunner runner = GradleRunner.create().withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(taskUnderTest.cleanIt(), taskUnderTest.toString()).withBuildCacheEnabled().withGradleUserHomeDirectory(testDirectory.resolve("user-home").toFile());
		runner.withArguments(args.toList());

		assertThat(succeeds(runner.withProjectDir(firstBuild.getLocation().toFile())).task(taskUnderTest), executed());
		assertThat(succeeds(runner.withProjectDir(firstBuild.getLocation().toFile())).task(taskUnderTest), fromCache());
		assertThat("restore from cache", succeeds(runner.withProjectDir(secondBuild.getLocation().toFile())).task(taskUnderTest), fromCache());
	}
}
