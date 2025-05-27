package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.platform.jni.fixtures.elements.CppGreeter;
import dev.nokee.platform.nativebase.fixtures.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.buildscript.syntax.Syntax.staticImportClass;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CppBinaryObjectsFunctionalTests {
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
			it.id("dev.nokee.native-companion");
		});
		build.getBuildFile().append(staticImportClass("dev.nokee.companion.CppBinaryObjects"));

		build.getBuildFile().append(groovyDsl("""
			def objectsTask = tasks.register('objects')
			components.withType(CppBinary) { binary ->
				objectsTask.configure {
					dependsOn(objectsOf(binary))
				}
			}
		""".stripIndent()));

		runner = runner.withTasks("objects");
	}

	abstract class Tester {
		BuildResult result;

		@Test
		void cppApplication() {
			new CppGreeterApp().writeToProject(testDirectory);
			build.getBuildFile().plugins(it -> {
				it.id("cpp-application");
			});
			result = runner.withTasks("objects").build();
			assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileReleaseCpp", ":objects"));
		}

		@Test
		void cppLibrary() {
			new CppGreeterLib().writeToProject(testDirectory);
			build.getBuildFile().plugins(it -> {
				it.id("cpp-library");
			});
			result = runner.withTasks("objects").build();
			assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileReleaseCpp", ":objects"));
		}

		@Test
		void cppUnitTest() {
			new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
			new CppGreeterTest().writeToProject(testDirectory);
			build.getBuildFile().plugins(it -> {
				it.id("cpp-unit-test");
			});
			result = runner.withTasks("objects").build();
			assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":objects"));
		}

		@Test
		void cppLibraryWithUnitTest() {
			new CppGreeterLib().writeToProject(testDirectory);
			new CppGreeterTest().writeToProject(testDirectory);
			build.getBuildFile().plugins(it -> {
				it.id("cpp-unit-test");
				it.id("cpp-library");
			});
			result = runner.withTasks("objects").build();
			assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":compileDebugCpp", ":compileReleaseCpp", ":objects"));
		}

		@Test
		void cppApplicationWithUnitTest() {
			new CppGreeterApp().writeToProject(testDirectory);
			new CppGreeterTest().writeToProject(testDirectory);
			build.getBuildFile().plugins(it -> {
				it.id("cpp-unit-test");
				it.id("cpp-application");
			});
			result = runner.withTasks("objects").build();
			assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":compileDebugCpp", ":compileReleaseCpp", ":objects"));
		}
	}

	@Nested
	class WithoutCCompilationTests extends Tester {}

	@Nested
	class WithCCompilationTests extends Tester {
		@BeforeEach
		void setup() {
			build.getBuildFile().append(groovyDsl("""
				components.withType(CppBinary) { binary ->
					compileTasks.addLater tasks.register("compile${binary.name - 'main' - 'Executable'}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("obj/main/c/${binary.name - 'main' - 'Executable'}")
					}
					compileTask.get().objectFileDir = layout.buildDirectory.dir("obj/main/cpp/${binary.name - 'main' - 'Executable'}"
				}
			""".stripIndent()));
		}

		@AfterEach
		void assertCCompile() {
			assertThat(result.getExecutedTaskPaths(), hasItems(matchesRegex("^:compile.+C$")));
		}
	}
}
