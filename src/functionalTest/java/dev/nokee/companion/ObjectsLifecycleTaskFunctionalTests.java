package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.platform.jni.fixtures.elements.CppGreeter;
import dev.nokee.platform.nativebase.fixtures.CppGreeterTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

class ObjectsLifecycleTaskFunctionalTests {
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
	}

	@Test
	void executesAllTaskProducingObjectsOfTheBinary() {
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);

		build.getBuildFile().append(groovyDsl("""
			unitTest { component ->
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${(binary.name - 'Executable').capitalize()}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'Executable'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		"""));
		BuildResult result = runner.withTasks("testObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":compileTestC", ":testObjects"));
	}

	@Test
	void executesAllTaskProducingObjectsOfTheExecutable() {
		build.getBuildFile().plugins(it -> it.id("cpp-application"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);

		build.getBuildFile().append(groovyDsl("""
			application { component ->
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${(binary.name - 'main').capitalize()}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		"""));
		BuildResult result = runner.withTasks("releaseObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":releaseObjects"));

		result = runner.withTasks("debugObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileDebugC", ":debugObjects"));
	}

	@Test
	void executesAllTaskProducingObjectsOfTheSharedLibrary() {
		build.getBuildFile().plugins(it -> it.id("cpp-library"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);

		build.getBuildFile().append(groovyDsl("""
			library { component ->
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${(binary.name - 'main').capitalize()}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		"""));
		BuildResult result = runner.withTasks("releaseObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":releaseObjects"));

		result = runner.withTasks("debugObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileDebugC", ":debugObjects"));
	}

	@Test
	void executesAllTaskProducingObjectsOfTheStaticLibrary() {
		build.getBuildFile().plugins(it -> it.id("cpp-library"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);

		build.getBuildFile().append(groovyDsl("""
			library { component ->
				linkage = [Linkage.STATIC]
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${(binary.name - 'main').capitalize()}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		"""));
		BuildResult result = runner.withTasks("releaseObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":releaseObjects"));

		result = runner.withTasks("debugObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileDebugC", ":debugObjects"));
	}

	@Test
	void executesAllTaskProducingObjectsOfTheBothLibrary() {
		build.getBuildFile().plugins(it -> it.id("cpp-library"));

		new CppGreeter().withSourceSetName("test").writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);

		build.getBuildFile().append(groovyDsl("""
			library { component ->
				linkage = [Linkage.STATIC, Linkage.SHARED]
				binaries.configureEach { binary ->
					compileTasks.addLater tasks.register("compile${(binary.name - 'main').capitalize()}C", CCompile) {
						toolChain = binary.toolChain
						targetPlatform = binary.targetPlatform.nativePlatform
						objectFileDir = layout.buildDirectory.dir("objs/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
				}
			}
		"""));
		BuildResult result = runner.withTasks("releaseSharedObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseSharedCpp", ":compileReleaseSharedC", ":releaseSharedObjects"));

		result = runner.withTasks("debugStaticObjects").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugStaticCpp", ":compileDebugStaticC", ":debugStaticObjects"));
	}
}
