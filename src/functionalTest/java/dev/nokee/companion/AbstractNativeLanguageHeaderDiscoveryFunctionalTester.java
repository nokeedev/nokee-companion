package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleProjectExtension;
import dev.nokee.commons.fixtures.GradleTaskUnderTestExtension;
import dev.nokee.commons.fixtures.TaskUnderTest;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.companion.fixtures.GradleTestKitMatchers.ExecutedBuild;
import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith({GradleProjectExtension.class, GradleTaskUnderTestExtension.class})
public interface AbstractNativeLanguageHeaderDiscoveryFunctionalTester {
	@Test
	default void canDiscoverHeaderFromDefinedMacros(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.write(build.file("src/main/headers/my-include-macro.h"), Arrays.asList("", "", ""), StandardOpenOption.APPEND);

		ExecutedBuild result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(result.task(taskUnderTest), output(taskPath -> containsString(String.format("Found all include files for '%s'", taskPath))));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroInclude(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		ExecutedBuild result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/a.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/b.cpp")));
		assertThat(result.task(taskUnderTest), not(output(containsString("/src/main/cpp/c.cpp"))));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeWithDuplicatedHeaders(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152-ex") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		ExecutedBuild result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/a.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/b.cpp")));
		assertThat(result.task(taskUnderTest), not(output(containsString("/src/main/cpp/c.cpp"))));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeWithDuplicatedHeadersHavingDifferentResolvingPath(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152-ex2") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.write(build.file("src/main/headers/f.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		ExecutedBuild result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/a.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/b.cpp")));
		assertThat(result.task(taskUnderTest), not(output(containsString("/src/main/cpp/c.cpp"))));
	}

	@Test
	default void discoverConsistentHeaderGraphOnMacroIncludeInvalidData(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-for-gradle-34152") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.fix-for-gradle-34152.enabled=false");

		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");
		ExecutedBuild result;

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.fix-for-gradle-34152.enabled=true");

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), performsFullRebuild());
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/a.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/b.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/c.cpp")));

		Files.write(build.file("src/main/headers/d.h"), Arrays.asList("", "// some new lines", ""), StandardOpenOption.APPEND);

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), not(performsFullRebuild()));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/a.cpp")));
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/b.cpp")));
		assertThat(result.task(taskUnderTest), not(output(containsString("/src/main/cpp/c.cpp"))));
	}

	@Test
	default void discoverDifferentMacroInclude(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);

		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i");
		ExecutedBuild result;

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.writeString(build.file("src/main/headers/my-other-include-macro.h"), """
			#ifndef _MY_OTHER_INCLUDE_MACRO_H_
			#define _MY_OTHER_INCLUDE_MACRO_H_
			#define RETURN_VALUE 52
			int foo();
			int other_foo();
			#endif
			""".stripIndent());

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).append("-Pinclude-file=my-other-include-macro.h").toList()));
		assertThat(result.task(taskUnderTest), executed());

		assertThat(result.task(taskUnderTest), performsFullRebuild());
		assertThat(result.task(taskUnderTest), output(containsString("/src/main/cpp/source-with-include-macros.cpp")));
	}

	@Test
	default void canReuseNativeCompileWhenRestoringFromBuildCache(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-unresolved-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);

		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i").append("-Dorg.gradle.internal.native.headers.unresolved.dependencies.ignore=true").withBuildCacheEnabled().requireOwnGradleUserHomeDirectory("build cache isolation");
		ExecutedBuild result;

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.walkFileTree(testDirectory.resolve(".gradle"), new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (dir.getFileName().toString().equals("nativeCompile")) {
					FileUtils.deleteDirectory(dir.toFile());
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		result = succeeds(runner.withArguments(args.withTasks("clean", taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), fromCache());
		assertThat(result.task(taskUnderTest), output(containsString("Cannot locate header file for '#include UNRESOLVED_MACRO' in source file 'a.cpp'.")));

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), upToDate());
		assertThat(result.task(taskUnderTest), not(output(containsString("Cannot locate header file for '#include UNRESOLVED_MACRO' in source file 'a.cpp'."))));
	}

	@Test
	default void canReuseNativeCompileWhenLocalProjectCacheClearedButNotBuildDirectory(TaskUnderTest taskUnderTest, @TempDir Path testDirectory, @GradleProject("project-with-unresolved-include-macros") GradleBuildElement project) throws IOException {
		GradleBuildElement build = project.writeToDirectory(testDirectory);

		GradleRunner runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();
		GradleRunnerArguments args = GradleRunnerArguments.create().append("-i").append("-Dorg.gradle.internal.native.headers.unresolved.dependencies.ignore=true");
		ExecutedBuild result;

		assertThat(theBuild(runner.withArguments(args.withTasks(taskUnderTest).toList())), becomesUpToDate());

		Files.walkFileTree(testDirectory.resolve(".gradle"), new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (dir.getFileName().toString().equals("nativeCompile")) {
					FileUtils.deleteDirectory(dir.toFile());
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), upToDate());
		assertThat(result.task(taskUnderTest), output(containsString("Cannot locate header file for '#include UNRESOLVED_MACRO' in source file 'a.cpp'.")));

		result = succeeds(runner.withArguments(args.withTasks(taskUnderTest).toList()));
		assertThat(result.task(taskUnderTest), upToDate());
		assertThat(result.task(taskUnderTest), not(output(containsString("Cannot locate header file for '#include UNRESOLVED_MACRO' in source file 'a.cpp'."))));
	}
}
