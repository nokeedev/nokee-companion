package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.templates.CppApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

class TaskDependenciesCopyFromFunctionalTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);
		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
		});

		build.getBuildFile().append(staticImportClass("dev.nokee.companion.util.CopyFromAction"));
	}

	@Test
	void doesNotDependOnCopiedTaskForNokeeCompile() {
		new CppApp().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		build.getBuildFile().append(groovyDsl("""
			application {
				binaries.configureEach { binary ->
					def task = tasks.register("newCompileFor${name.capitalize()}", CppCompile)
					task.configure(copyFrom(compileTask))
					task.configure {
						objectFileDir = layout.buildDirectory.dir("objs/${binary.name}")
					}
				}
			}
		""".stripIndent()));

		BuildResult result = runner.withTasks("newCompileForMainRelease").build();
		assertThat(result.getExecutedTaskPaths(), contains(":newCompileForMainRelease"));
	}

	@Test
	void doesNotDependOnCopiedTaskForCoreCompile() {
		new CppApp().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		build.getBuildFile().append(importClass("dev.nokee.language.cpp.tasks.CppCompile"));
		build.getBuildFile().append(groovyDsl("""
			application {
				binaries.configureEach { binary ->
					def task = tasks.register("newCompileFor${name.capitalize()}", CppCompile.clazz())
					task.configure(copyFrom(compileTask))
					task.configure {
						objectFileDir = layout.buildDirectory.dir("objs/${binary.name}")
					}
				}
			}
		""".stripIndent()));
		BuildResult result = runner.withTasks("newCompileForMainRelease").build();
		assertThat(result.getExecutedTaskPaths(), contains(":newCompileForMainRelease"));
	}

	@Test
	void doesNotDependOnCopiedTaskForCoreLinkExecutable() {
		new CppApp().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		build.getBuildFile().append(groovyDsl("""
			application {
				binaries.configureEach { binary ->
					def task = tasks.register("newLinkFor${binary.name.capitalize()}", LinkExecutable)
					task.configure(copyFrom(linkTask))
					task.configure {
						linkedFile = layout.buildDirectory.file("libs/${binary.name}/${project.name}")
					}
				}
			}
		""".stripIndent()));
		BuildResult result = runner.withTasks("newLinkForMainRelease").build();
		assertThat(result.getExecutedTaskPaths(), contains(":compileReleaseCpp", ":newLinkForMainRelease"));
	}

	@Test
	void doesNotDependOnCopiedTaskForCoreLinkSharedLibrary() {
		new CppApp().getLibs().writeToProject(testDirectory);
		build.getBuildFile().plugins(it -> it.id("cpp-library"));
		build.getBuildFile().append(groovyDsl("""
			library {
				binaries.configureEach { binary ->
					def task = tasks.register("newLinkFor${binary.name.capitalize()}", LinkSharedLibrary)
					task.configure(copyFrom(linkTask))
					task.configure {
						linkedFile = layout.buildDirectory.file("libs/${binary.name}/${project.name}")
					}
				}
			}
		""".stripIndent()));
		BuildResult result = runner.withTasks("newLinkForMainRelease").build();
		assertThat(result.getExecutedTaskPaths(), contains(":compileReleaseCpp", ":newLinkForMainRelease"));
	}
}
