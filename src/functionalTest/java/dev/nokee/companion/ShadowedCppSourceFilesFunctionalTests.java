package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.templates.CppApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.blocks.BuildscriptBlock.classpath;
import static dev.gradleplugins.buildscript.blocks.DependencyNotation.files;
import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.buildscript.syntax.Syntax.staticImportClass;
import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureCause;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

class ShadowedCppSourceFilesFunctionalTests {
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
		});
		build.getBuildFile().append(staticImportClass("dev.nokee.companion.CppSourceFiles"));

		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(testDirectory);
	}

	@Test
	void keepsCppSourceAddedViaProjectConfiguration() throws IOException {
		build.getBuildFile().plugins(it -> it.id("dev.nokee.native-companion"));
		build.getBuildFile().append(groovyDsl("""
			tasks.withType(CppCompile).configureEach {
				source(file('broken.cpp')) // could be generated task
			}
		""".stripIndent()));

		// we are using a broken file to make the assertion easier
		Files.writeString(testDirectory.resolve("broken.cpp"), "broken!");

		BuildResult result = runner.withTasks("compileReleaseCpp").buildAndFail();
		assertThat(result, hasFailureCause("C++ compiler failed while compiling broken.cpp."));
	}

	@Test
	void warnsCppCompileSourceChangesAreDisallowedWhenLockedViaProjectConfiguration() {
		build.getSettingsFile().buildscript(it -> it.dependencies(classpath(files(runner.getPluginClasspath()))));
		build.getBuildFile().append(groovyDsl("""
			components.withType(CppBinary) { binary ->
				tasks.named("compile${binary.name - 'main'}Cpp") {
					source.disallowChanges()
				}
			}

			apply plugin: 'dev.nokee.native-companion'
		""".stripIndent()));

		BuildResult result = runner.withTasks("compileReleaseCpp").build();
		assertThat(result.getOutput(), containsString("Could not wire shadowed 'cppSource' from C++ binary 'mainRelease' in root project 'test' to task ':compileReleaseCpp'."));
	}

	@Test
	void ensuresCppBinarySourceShadowPropertyConfiguredEarly() throws IOException {
		build.getBuildFile().plugins(it -> it.id("dev.nokee.native-companion"));
		build.getBuildFile().append(groovyDsl("""
			application {
				binaries.whenElementKnown { binary ->
					cppSourceOf(binary).set(cppSourceOf(binary).get() + files('broken.cpp')) // could be generated task
				}
			}
		""".stripIndent()));

		// we are using a broken file to make the assertion easier
		Files.writeString(testDirectory.resolve("broken.cpp"), "broken!");

		BuildResult result = runner.withTasks("compileReleaseCpp").buildAndFail();
		assertThat(result, hasFailureCause("C++ compiler failed while compiling broken.cpp."));
	}
}
