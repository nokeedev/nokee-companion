package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.templates.CppList;

import java.io.IOException;
import java.nio.file.Files;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.nokee.elements.core.ProjectElement.ofMain;

class CppLibraryPluginFunctionalTests implements AbstractGeneratedPublicHeadersFunctionalTests, AbstractGeneratedPrivateHeadersFunctionalTests {
	@GradleProject("project-with-generated-public-headers")
	public static GradleBuildElement makeProjectWithGeneratedPublicHeaders() throws IOException {
		GradleBuildElement result = GradleBuildElement.empty();
		result.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
			it.id("cpp-library");
		});
		result.getBuildFile().append(groovyDsl("""
				def generatorTask = tasks.register('generatePublicHeaders', Sync) {
					into layout.buildDirectory.dir('generated-src/main/public')
					from 'staging-public-headers'
				}
				library {
					publicHeaders.from(generatorTask)
				}

				tasks.register('verify') { dependsOn('compileDebugCpp') }
			""".stripIndent()));

		Files.writeString(result.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");

		new GradleLayoutElement().applyTo(ofMain(new CppList().withoutPublicHeaders())).writeToDirectory(result.getLocation());
		new CppList().getPublicHeaders().writeToDirectory(result.getLocation().resolve("staging-public-headers"));

		return result;
	}

	@GradleProject("project-with-generated-private-headers")
	public static GradleBuildElement makeProjectWithGeneratedPrivateHeaders() throws IOException {
		GradleBuildElement result = GradleBuildElement.empty();
		result.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
			it.id("cpp-library");
		});
		result.getBuildFile().append(groovyDsl("""
				def generatorTask = tasks.register('generatePrivateHeaders', Sync) {
					into layout.buildDirectory.dir('generated-src/main/headers')
					from 'staging-private-headers'
				}
				library {
					privateHeaders.from(generatorTask)
				}

				tasks.register('verify') { dependsOn('compileDebugCpp') }
			""".stripIndent()));

		Files.writeString(result.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");

		new GradleLayoutElement().applyTo(ofMain(new CppList().withoutPrivateHeaders())).writeToDirectory(result.getLocation());
		new CppList().getPrivateHeaders().writeToDirectory(result.getLocation().resolve("staging-private-headers"));

		return result;
	}
}
