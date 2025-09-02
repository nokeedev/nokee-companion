package dev.nokee.companion;

import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.templates.CppApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.elements.core.ProjectElement.ofMain;

class CompileOnlyApiFunctionalTests {
	@TempDir Path testDirectory;
	GradleBuild main;

	@BeforeEach
	void setup() {
		CppApp app = new CppApp();
		main = GradleBuild.inDirectory(testDirectory);

		new GradleLayoutElement().applyTo(ofMain(app.withoutImplementation())).writeToDirectory(main.getLocation());
		main.rootProject(buildFile -> {
			buildFile.plugins(it -> {
				it.id("cpp-application");
				it.id("dev.nokee.native-companion");
			});
			buildFile.append(groovyDsl("""
				dependencies {
					implementation project(':lib')
				}
			"""));
		});
		main.subproject("lib", project -> {
			new GradleLayoutElement().applyTo(ofMain(app.getLibs().withoutPublicHeaders())).writeToDirectory(project.getLocation());
			project.buildFile.plugins(it -> {
				it.id("cpp-library");
				it.id("dev.nokee.native-companion");
			});
			project.buildFile.append(groovyDsl("""
				dependencies {
					compileOnlyApi project(':api')
				}
			"""));
		});
		main.subproject("api", project ->  {
			app.getLibs().getPublicHeaders().writeToDirectory(project.getLocation().resolve("includes"));
			project.buildFile.append(groovyDsl("""
				configurations.consumable('cppApiElements') {
					attributes {
						attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
					}
					outgoing {
						artifact(file('includes')) {
							type = 'directory'
						}
					}
				}
			"""));
		});


	}

	@Test
	void canCompileApplicationDependentOnLibraryUsingCompileOnlyApiBucket() {
		GradleRunner.create(gradleTestKit()).inDirectory(main.getLocation()).forwardOutput().withPluginClasspath().withTasks("assembleDebug", "assembleRelease").build();
	}
}
