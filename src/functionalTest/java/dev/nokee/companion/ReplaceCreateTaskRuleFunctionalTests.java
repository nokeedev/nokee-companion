package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.blocks.BuildscriptBlock.classpath;
import static dev.gradleplugins.buildscript.blocks.DependencyNotation.files;
import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.buildscript.syntax.Syntax.importClass;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ReplaceCreateTaskRuleFunctionalTests {
	GradleBuild build;
	GradleRunner runner;

	@BeforeEach
	void setup(@TempDir Path testDirectory) throws IOException {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.settingsFile(settings -> {
			settings.buildscript(buildscript -> {
				buildscript.dependencies(classpath(files(runner.getPluginClasspath())));
			});
		});
		build.rootProject(project -> {
			project.plugins(plugins -> {
				plugins.id("dev.nokee.native-companion");
				plugins.id("cpp-library");
			});
			project.append(importClass("dev.nokee.nativeplatform.tasks.Ar"));
			project.append(groovyDsl("""
				library {
					baseName = 'main'
					linkage = [Linkage.STATIC]
				}
			"""));
		});

		Files.createDirectories(build.getLocation().resolve("src/main/cpp"));
		Files.writeString(build.getLocation().resolve("src/main/cpp/foo.cpp"), "int foo() { return 42; }\n");
	}

	@Test
	void replacesCreateTaskOfStaticLibraryBinaries() {
		build.rootProject(project -> {
			project.append(groovyDsl("""
				tasks.register('verify') {
					def createTask = library.developmentBinary.flatMap { it.createTask }
					doLast {
						assert createTask.get() instanceof Ar
					}
				}
			"""));
		});

		runner.withArguments("verify").build();
	}

	@Test
	void createsStaticLibraryOfBinaries() {
		BuildResult result = runner.withArguments("assembleDebug").build();

		assertThat(from(result), tasksExecuted(hasItem(":createDebug")));
		assertThat(Files.exists(build.getLocation().resolve(OperatingSystem.current().getStaticLibraryName("build/lib/main/debug/main"))), is(true));
	}
}
