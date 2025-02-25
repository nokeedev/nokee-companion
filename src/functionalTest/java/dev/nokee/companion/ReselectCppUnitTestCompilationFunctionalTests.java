package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.platform.nativebase.fixtures.CGreeterApp;
import dev.nokee.platform.nativebase.fixtures.CGreeterTest;
import dev.nokee.platform.nativebase.fixtures.CppGreeterApp;
import dev.nokee.platform.nativebase.fixtures.CppGreeterTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

class ReselectCppUnitTestCompilationFunctionalTests {
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
			it.id("cpp-application");
			it.id("dev.nokee.native-companion");
		});

		build.getBuildFile().append(groovyDsl("""
			application { component ->
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

		new CppGreeterApp().writeToProject(testDirectory);
	}

	@Test
	void canReselectTestedBinaryToOptimizedVariant() {
		new CppGreeterTest().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));
		build.getBuildFile().append(importClass("dev.nokee.companion.util.TestedBinaryMapper"));
		build.getBuildFile().append(staticImportClass("dev.nokee.companion.CppUnitTestExtensions"));
		build.getBuildFile().append(groovyDsl("""
			unitTest { component ->
				binaries.configureEach { binary ->
					testedBinaryOf(binary).set(testedComponentOf(component).map(new TestedBinaryMapper(binary) {
						@Override
						protected boolean isTestedBinary(CppTestExecutable testExecutable, ProductionCppComponent mainComponent, CppBinary testedBinary) {
							return testedBinary.getTargetMachine().getOperatingSystemFamily().getName().equals(testExecutable.getTargetMachine().getOperatingSystemFamily().getName())
								&& testedBinary.getTargetMachine().getArchitecture().getName().equals(testExecutable.getTargetMachine().getArchitecture().getName())
								&& testedBinary.isOptimized()
								&& hasDevelopmentBinaryLinkage(mainComponent, testedBinary);
						}
					}))
				}
			}
		""".stripIndent()));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":compileTestCpp", ":relocateMainForTest", ":linkTest", ":runTest"));
	}
}
