package dev.nokee.companion;

import dev.gradleplugins.buildscript.io.GradleBuildFile;
import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.CoverageObjectMockPluginFixture;
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
import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureCause;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

class CppUnitTestForApplicationLinkAgainstLibraryTransitiveFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();//.withGradleVersion("7.6.4");
		build.getSettingsFile().plugins(it -> it.id("dev.nokee.native-companion"));
		build.getSettingsFile().append(groovyDsl("""
			rootProject.name = 'test'
			include 'impl'
		"""));
		GradleBuildFile.inDirectory(build.dir("impl")).plugins(it -> it.id("cpp-library")).append(groovyDsl("""
			library.linkage = [Linkage.SHARED, Linkage.STATIC]
		"""));
		build.getBuildFile().plugins(it -> {
			it.id("cpp-application");
		});
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));
		build.getBuildFile().append(groovyDsl("""
			application {
				dependencies {
					implementation project(':impl')
				}
			}
		"""));


		new GradleLayoutElement().applyTo(ofMain(new CppGreeterApp().getElementUsingGreeter()).withTest(new CppGreeterTest())).writeToDirectory(testDirectory);
		new GradleLayoutElement().applyTo(ofMain(new CppGreeterApp().getGreeter())).writeToDirectory(testDirectory.resolve("impl"));
	}

	@Test
	void objectFiles__canReselectTestedBinaryToOptimizedVariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asObjects()
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":linkDebug")));
	}

	@Test
	void releaseobjectFiles__canReselectTestedBinaryToOptimizedVariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asObjects()
				}
			}
			unitTest {
				binaries.configureEach {
					ext.optimized = true
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":linkRelease")));
	}

	@Test
	void product__debugvariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asProduct()
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").buildAndFail();
		assertThat(result, hasFailureCause("Cannot integrate as product for application"));
	}

	@Test
	void sourceFiles__zzzcanReselectTestedBinaryToOptimizedVariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asSources()
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").buildAndFail();
		assertThat(result, hasFailureCause("Cannot integrate as sources for application"));
	}

	@Test
	void objectFiles__canSelectCoverageObjects() {
		build.getBuildFile().append(groovyDsl(new CoverageObjectMockPluginFixture().asGroovyScript()));
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation(testedComponent(project).asObjects()) {
						attributes {
							attribute(Attribute.of("coverage", String), "yes")
						}
					}
				}
				binaries.configureEach {
					ext.optimized = linkTask.flatMap { it.linkerArgs }.map { !it.contains('--coverage') }
					linkTask.get().linkerArgs.add('--coverage')
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileCoverageCpp", ":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":linkDebugShared", ":linkReleaseShared", ":linkDebugStatic", ":linkReleaseStatic")));
	}
}
