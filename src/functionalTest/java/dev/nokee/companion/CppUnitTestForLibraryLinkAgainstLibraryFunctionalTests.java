package dev.nokee.companion;

import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
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
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput().withGradleVersion("7.6.4");
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
		build.getBuildFile().plugins(it -> {
			it.id("dev.nokee.native-companion");
			it.id("cpp-library");
		});
		build.getBuildFile().plugins(it -> it.id("cpp-unit-test"));

		new CppGreeterLib().writeToProject(testDirectory);
		new CppGreeterTest().writeToProject(testDirectory);
	}

	@Test
	void objectFiles__canReselectTestedBinaryToOptimizedVariant() {
		build.getBuildFile().append(groovyDsl("""
			unitTest {
				testedComponent {
					linkAgainst(objects)
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
				testedComponent {
					linkAgainst(objects)
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
				testedComponent {
					linkAgainst(product)
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
				testedComponent {
					linkAgainst(product)
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
				testedComponent {
					linkAgainst(product)
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
				testedComponent {
					linkAgainst(sources)
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
				testedComponent {
					linkAgainst(sources)
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
		build.getBuildFile().append(groovyDsl("""
			import dev.nokee.commons.names.CppNames
			import dev.nokee.commons.names.NamingScheme
			import dev.nokee.language.cpp.tasks.CppCompile
			import dev.nokee.companion.ObjectFiles

			import static dev.nokee.companion.util.CopyFromAction.copyFrom

			library {
				binaries.configureEach {
					if (!optimized) {
						def names = CppNames.of(it)//.with('buildTypeName', 'coverage')
						def coverageTask = tasks.register(names.taskName('compile', 'cpp').toString().replace('Debug', 'Coverage'), CppCompile.clazz())
						coverageTask.configure(copyFrom(compileTask))
						coverageTask.configure {
							objectFileDir = layout.buildDirectory.dir("obj/" + names.toString(NamingScheme.dirNames()).replace('debug', 'coverage'))
							compilerArgs.add('-coverage')
						}
						def linkElements = configurations.named(CppNames.qualifyingName(it).toString() + 'TestableLinkElements')
						linkElements.configure {
							outgoing {
								variants.create('coverage-objects') {
									artifact(tasks.register('syncCoverageObjects', Sync) { task ->
										task.from(coverageTask.map { ObjectFiles.of(it) })
										task.into(layout.buildDirectory.dir("tmp/${task.name}"))
									}) { type = 'public.object-code-directory'}
								}
							}
						}
						configurations.named(CppNames.qualifyingName(it).toString() + 'TestableSourceElements') {
							outgoing {
								variants.create('coverage-objects')
							}
						}
					}
				}
			}
			unitTest {
				testedComponent {
					linkAgainst("coverage-objects")
				}
				binaries.configureEach {
					println 'set optimized ...'
					ext.optimized = linkTask.flatMap { it.linkerArgs }.map {
						new Throwable((it + ' -- ' + name).toString()).printStackTrace()
						return !it.contains('--coverage')
					}
					linkTask.get().linkerArgs.add('--coverage')
					println 'after set coverage args'
				}
			}
		"""));

		runner.withTasks("outgoingVariants").build();
		runner.withTasks("resolvableConfigurations").build();
//		runner.publishBuildScans().withTasks("dependencies").build();
		BuildResult result = runner.publishBuildScans().withTasks("runTest").build();
		assertThat(result.getExecutedTaskPaths(), hasItems(":compileCoverageCpp", ":compileTestCpp", ":linkTest", ":runTest"));
		assertThat(result.getExecutedTaskPaths(), not(hasItems(":linkDebug", ":linkRelease")));
	}
}
