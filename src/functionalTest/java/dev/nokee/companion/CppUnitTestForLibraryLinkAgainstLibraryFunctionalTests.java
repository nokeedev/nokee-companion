package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.fixtures.CoverageObjectMockPluginFixture;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.platform.nativebase.fixtures.CppGreeterLib;
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
import static org.hamcrest.Matchers.not;

class CppUnitTestForLibraryLinkAgainstLibraryFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();//.withGradleVersion("7.6.4");
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
			it.id("cpp-library");
		});
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));

		new GradleLayoutElement().applyTo(new CppGreeterLib().withGenericTestSuite()).writeToDirectory(testDirectory);
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

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":linkDebug", ":compileTestCpp", ":linkTest", ":runTest"));
	}

	@Test
	void product__debugvariant_so_version() {
		build.getBuildFile().append(groovyDsl("""
			library {
				binaries.configureEach {
					def linkedFile = linkTask.get().linkedFile.get().asFile
					linkTask.get().linkedFile.set(new File(linkedFile.absolutePath + ".1.0.2"))
				}
			}
			unitTest {
				dependencies {
					implementation testedComponent(project)
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileDebugCpp", ":linkDebug", ":compileTestCpp", ":linkTest", ":runTest"));
	}

	@Test
	void releaseproduct__zzcanReselectTestedBinaryToOptimizedVariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asProduct()
				}
			}
			unitTest {
				binaries.configureEach {
					ext.optimized = true
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileReleaseCpp", ":linkRelease", ":compileTestCpp", ":linkTest", ":runTest"));
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

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":compileDebugCpp", ":linkDebug")));
	}

	@Test
	void releasesourceFiles__zzzcanReselectTestedBinaryToOptimizedVariant() throws IOException {
		Files.writeString(build.file("src/main/cpp/release-only.cpp"), """
		#ifndef RELEASE
		# error "NOT IN RELEASE"
		#endif
		""");
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				dependencies {
					implementation testedComponent(project).asSources()
				}
			}
			unitTest {
				binaries.configureEach {
					ext.optimized = true
					compileTask.get().with { task ->
						task.compilerArgs.add(task.options.optimized.map { it ? '-DRELEASE' : '-DDEBUG' })
					}
				}
			}
		"""));

		BuildResult result = runner.withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":compileDebugCpp", ":linkDebug")));
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
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":linkDebug", ":linkRelease")));
	}
}
