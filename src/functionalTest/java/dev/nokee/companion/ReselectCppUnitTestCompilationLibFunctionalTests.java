package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.platform.nativebase.fixtures.CppGreeterApp;
import dev.nokee.platform.nativebase.fixtures.CppGreeterLib;
import dev.nokee.platform.nativebase.fixtures.CppGreeterTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.buildscript.syntax.Syntax.staticImportClass;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

class ReselectCppUnitTestCompilationLibFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
		build.getBuildFile().plugins(it -> {
			it.id("cpp-library");
			it.id("dev.nokee.native-companion");
		});

		build.getBuildFile().append(groovyDsl("""
			library { component ->
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${binary.name - 'main'}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		""".stripIndent()));

		new CppGreeterLib().writeToProject(testDirectory);
	}

	@Test
	void canReselectTestedBinaryToOptimizedVariant() {
		new CppGreeterTest().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));
		build.getBuildFile().append(staticImportClass("dev.nokee.companion.CppBinaryProperties"));
		build.getBuildFile().append(groovyDsl("""
			unitTest { component ->
				binaries.configureEach { binary ->
					optimizationOf(binary).set(true)
				}
			}
		""".stripIndent()));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":compileTestCpp", ":linkTest", ":runTest"));
	}
}
