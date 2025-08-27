package dev.nokee.companion;

import dev.gradleplugins.buildscript.syntax.Syntax;
import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.templates.CppApp;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.fixtures.runnerkit.BuildResultMatchers.hasFailureCause;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

class OverlinkingAvoidanceFunctionalTests {
	@TempDir Path testDirectory;
	GradleBuild vendors;
	GradleBuild main;
	GradleRunner runner;

	@BeforeEach
	void setup() {
		var app = new CppApp();

		// Poor-man publication of only the release build type
		vendors = GradleBuild.inDirectory(testDirectory.resolve("vendors"))
			.subproject("list", project -> {
				// TODO: writeToDirectory should understand directory-like object like GradleProject
				new GradleLayoutElement().applyTo(ofMain(app.getList())).writeToDirectory(project.getLocation());

				project.buildFile.plugins(it -> {
					it.id("cpp-library");
					it.id("maven-publish");
				});
				project.buildFile.append(groovyDsl("""
					group = 'com.example.vendors'
					version = '2.4'

					// lazily use ProjectInternal#getServices()
					components.add(services.get(SoftwareComponentFactory).adhoc('cpp'))
					afterEvaluate {
						configurations.cppApiElements {
							outgoing.variants.create('zipped') {
								attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, 'zip')
								artifact(tasks.cppHeaders)
							}
						}
					}
					components.cpp.addVariantsFromConfiguration(configurations.cppApiElements) { details ->
						if (details.configurationVariant.artifacts.any { it.type != 'zip' }) { details.skip() }
					}
					components.withType(CppBinary).matching { it.optimized }.all {
						components.cpp.addVariantsFromConfiguration(configurations.releaseLinkElements) {}
						components.cpp.addVariantsFromConfiguration(configurations.releaseRuntimeElements) {}
					}
					publishing {
						publications.create('cpp', MavenPublication) {
							from components.cpp
						}
						repositories.maven { url = '%s' }
					}
				""".formatted(project.getLocation().relativize(testDirectory.resolve("repo")))));
			});
		GradleRunner.create(gradleTestKit()).inDirectory(vendors.getLocation()).withArguments(":list:publishCppPublicationToMavenRepository").build();

		main = GradleBuild.inDirectory(testDirectory.resolve("main"))
			.subproject("app", project -> {
				new GradleLayoutElement().applyTo(ofMain(app.withoutImplementation())).writeToDirectory(project.getLocation());
				project.buildFile.plugins(it -> {
					it.id("cpp-application");
					it.id("dev.nokee.native-companion");
				});
				project.buildFile.append(groovyDsl("""
					repositories.maven { url = '%s' }

					application {
						dependencies {
							implementation project(':lib')
							implementation 'com.example.vendors:list:+'
						}
					}
				""".formatted(project.getLocation().relativize(testDirectory.resolve("repo")))));
			})
			.subproject("lib", project -> {
				new GradleLayoutElement().applyTo(ofMain(app.getUtilities())).writeToDirectory(project.getLocation());
				project.buildFile.plugins(it -> {
					it.id("cpp-library");
					it.id("dev.nokee.native-companion");
				});
				project.buildFile.append(groovyDsl("""
					repositories.maven { url = '%s' }

					library {
						dependencies {
							implementation 'com.example.vendors:list:+'
						}
					}
				""".formatted(project.getLocation().relativize(testDirectory.resolve("repo")))));
			});

		runner = GradleRunner.create(gradleTestKit()).withPluginClasspath().inDirectory(main.getLocation()).forwardOutput();
	}

	@Test
	void test() {
		main.subproject("lib", project -> {
			project.buildFile.append(Syntax.staticImportClass("dev.nokee.companion.util.CopyFromAction"));
			project.buildFile.append(groovyDsl("""
					components.withType(CppBinary).matching { !it.optimized }.all { binary ->
						def linkTask = tasks.register('linkDebugOpt', LinkSharedLibrary);
						linkTask.configure(copyFrom(binary.linkTask))
						linkTask.configure { task ->
							task.libs.setFrom(configurations.nativeLinkRelease)
							task.linkedFile.fileProvider(binary.linkTask.flatMap { it.linkedFile.locationOnly }.map { new File(it.asFile.absolutePath.replace('debug', 'debugOpt')) })
						}
						binary.linkTask.get().mustRunAfter(linkTask) // force the copied task to run BEFORE to assert copied args doesn't contain any linkerArgs
					}
				"""));
		});
		BuildResult result = runner.withArgument("-Pdev.nokee.native-companion.overlinking-avoidance.enabled=true").withArgument("-i").withArgument(":lib:linkDebugOpt").withArgument(":lib:linkDebug").buildAndFail();
		assertThat(result.task(":lib:linkDebugOpt").getOutput(), containsString("Overlinking avoidance linker args from task ':lib:linkDebug' are not consider."));
		assertThat(result, hasFailureCause(overlinkingFailure()));
	}

	static String overlinkingFailure() {
		if (SystemUtils.IS_OS_LINUX) {
			return "Error while evaluating property 'linkerArgs' of task ':lib:linkDebug'";
		}
		return "Could not resolve all files for configuration ':lib:nativeLinkDebug'.";
	}
}
