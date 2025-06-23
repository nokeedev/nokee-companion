package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.platform.nativebase.fixtures.CppGreeterApp;
import dev.nokee.platform.nativebase.fixtures.CppGreeterTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.elements.core.ProjectElement.ofTest;
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
						objectFileDir = layout.buildDirectory.dir("obj/main/c/${binary.name - 'main'}")
						source(fileTree('src/main/c'))
						includes(component.privateHeaderDirs)
					}
					compileTask.get().objectFileDir = layout.buildDirectory.dir("obj/main/cpp/${binary.name - 'main' - 'Executable'}")
				}
			}
		""".stripIndent()));

		new GradleLayoutElement().applyTo(new CppGreeterApp()).writeToDirectory(testDirectory);
	}

	@Test
	void canReselectTestedBinaryToOptimizedVariant() {
		new GradleLayoutElement().applyTo(ofTest(new CppGreeterTest())).writeToDirectory(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));
		build.getBuildFile().append(groovyDsl("""
			unitTest { component ->
				dependencies {
					implementation(testedComponent(project).asObjects()) {
						attributes {
							attribute(CppBinary.OPTIMIZED_ATTRIBUTE, true)
						}
					}
				}
				binaries.configureEach { binary ->
//					// Turn the whole test executable to a release build type (tested component, test component dependency and test binary itself).
//				    ext.optimized = true;
//
//					def qualifyingName = (name - 'Executable').uncapitalize()
//
//					// Leave the current test binary as debug build type
//				    tasks.named("compile${qualifyingName.capitalize()}Cpp") { optimized = false }
//
//				    // Leave the test binary dependencies as debug build type
//				    configurations.named("cppCompile${qualifyingName.capitalize()}") { attributes.attribute(CppBinary.OPTIMIZED_ATTRIBUTE, false) }
//				    configurations.named("nativeLink${qualifyingName.capitalize()}") { attributes.attribute(CppBinary.OPTIMIZED_ATTRIBUTE, false) }
//				    configurations.named("nativeRuntime${qualifyingName.capitalize()}") { attributes.attribute(CppBinary.OPTIMIZED_ATTRIBUTE, false) }
				}
			}
		""".stripIndent()));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileReleaseC", ":compileTestCpp", ":linkTest", ":runTest"));
	}
}
