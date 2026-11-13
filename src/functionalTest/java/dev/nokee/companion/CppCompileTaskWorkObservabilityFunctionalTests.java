package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.companion.fixtures.WorkObservabilityFixtures.ObservedWorkItem;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.blocks.BuildscriptBlock.classpath;
import static dev.gradleplugins.buildscript.blocks.DependencyNotation.files;
import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.buildscript.syntax.Syntax.importClass;
import static dev.gradleplugins.buildscript.syntax.Syntax.staticImportClass;
import static dev.nokee.companion.fixtures.GradleRunnerProperties.forConfigurationCacheEnabled;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static dev.nokee.companion.fixtures.WorkObservabilityFixtures.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CppCompileTaskWorkObservabilityFunctionalTests {
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
				plugins.id("lifecycle-base");
			});
			project.append(importClass("dev.nokee.language.cpp.tasks.CppCompile"));
			project.append(importClass("dev.nokee.work.WorkCompletionListener"));
			project.append(importClass(BuildService.class));
			project.append(importClass(BuildServiceParameters.class));
			project.append(importClass(NativeToolChainRegistry.class));
			project.append(importClass(ToolType.class));
			project.append(staticImportClass(DefaultNativePlatform.class));
			project.append(apply(plugin(StandardToolChainsPlugin.class)));
			project.append(groovyDsl("""
				def toolChains = project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

				def generateHeaders = tasks.register('generateHeaders') {
					def outputDirectory = layout.buildDirectory.dir('generated/headers')
					outputs.dir(outputDirectory)
					doLast { outputDirectory.get().file('foo.h').asFile.text = 'int foo();' }
				}

				tasks.register('compile', CppCompile.clazz()) {
					toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
					targetPlatform = host()
					systemIncludes.from(toolChain.zip(targetPlatform) { toolchain, platform -> toolchain.select(platform).getSystemLibraries(ToolType.CPP_COMPILER).includeDirs })
					objectFileDir = layout.buildDirectory.dir('obj/main')
					source.from(fileTree('src/main/cpp'))
					includes.from(generateHeaders)
				}
			"""));
		});
		build.rootProject(withWorkItemLines());

		Files.createDirectories(build.getLocation().resolve("src/main/cpp"));
		Files.writeString(build.getLocation().resolve("src/main/cpp/foo.cpp"), "int foo() { return 42; }\n");
		Files.writeString(build.getLocation().resolve("src/main/cpp/main.cpp"), "int foo();\nint main() { return foo() - 42; }\n");
	}

	static Matcher<Iterable<? extends ObservedWorkItem>> expectedWorkItems() {
		return containsInAnyOrder(compilationOf("foo.cpp"), compilationOf("main.cpp"));
	}

	static Consumer<GradleBuild.GradleProject> withPerSourceOptions() {
		return project -> {
			project.append(groovyDsl("""
				tasks.named('compile', CppCompile) {
					source(file('src/main/cpp/foo.cpp')) { compilerArgs.add('-DFOO_BUCKET') }
				}
			"""));
		};
	}

	@Nested
	class WorkPlanTests {
		@BeforeEach
		void registerPrintWorkPlanTask() {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					abstract class PrintWorkPlan extends DefaultTask {
						@Input abstract ListProperty<CppCompile.WorkItem> getWorkPlan()

						@TaskAction
						void print() {
							workPlan.get().each { println "WORK PLAN ${WorkItemLines.of(it)}" }
						}
					}

					tasks.register('printWorkPlan', PrintWorkPlan) {
						workPlan = tasks.named('compile', CppCompile).flatMap { it.workPlan }
					}
				"""));
			});
		}

		@Test
		void plansEveryWorkItem() {
			BuildResult result = runner.withArguments("printWorkPlan").build();

			assertThat(theWorkPlan(result), expectedWorkItems());
		}

		@Test
		void plansEveryWorkItemWhenUpToDate() {
			runner.withArguments(":compile", "printWorkPlan").build();

			BuildResult result = runner.withArguments(":compile", "printWorkPlan").build();

			assertThat(from(result).task(":compile"), upToDate());
			assertThat(theWorkPlan(result), expectedWorkItems());
		}

		@Test
		void consumingWorkPlanRunsInputProducersButNotTheTask() {
			BuildResult result = runner.withArguments("printWorkPlan").build();

			assertThat(from(result), tasksExecuted(allOf(hasItem(":generateHeaders"), not(hasItem(":compile")))));
		}

		@Test
		void plansEveryWorkItemWhenRestoredFromCache() {
			GradleRunnerArguments args = GradleRunnerArguments.create().withBuildCacheEnabled();
			runner.withArguments(args.withTasks(":compile").toList()).build();
			runner.withArguments(args.withTasks("clean").toList()).build();

			BuildResult result = runner.withArguments(args.withTasks(":compile", "printWorkPlan").toList()).build();

			assertThat(from(result).task(":compile"), fromCache());
			assertThat(theWorkPlan(result), expectedWorkItems());
		}

		@Test
		void plansEveryWorkItemWithPerSourceOptions() {
			build.rootProject(withPerSourceOptions());

			BuildResult result = runner.withArguments("printWorkPlan").build();

			assertThat(theWorkPlan(result), containsInAnyOrder(
				allOf(compilationOf("foo.cpp"), withArgument("-DFOO_BUCKET")),
				allOf(compilationOf("main.cpp"), not(withArgument("-DFOO_BUCKET")))
			));
		}
	}

	@Nested
	class WorkCompletionEventTests {
		@BeforeEach
		void enableConfigurationCache() {
			build.properties(properties -> {
				properties.putAll(forConfigurationCacheEnabled());
			});
		}

		@Test
		void emitsEveryWorkItem() {
			build.rootProject(withWorkListener());

			BuildResult result = runner.withArguments(":compile").build();

			assertThat(theEvents(result).forTask(":compile"), expectedWorkItems());
		}

		@Test
		void emitsNothingWhenUpToDate() {
			build.rootProject(withWorkListener());
			runner.withArguments(":compile").build();

			BuildResult result = runner.withArguments(":compile").build();

			assertThat(theEvents(result).forTask(":compile"), empty());
		}

		@Test
		void emitsFailedWorkItem() throws IOException {
			build.rootProject(withWorkListener());
			Files.writeString(build.getLocation().resolve("src/main/cpp/foo.cpp"), "int foo( { return 42; }\n");

			BuildResult result = runner.withArguments(":compile").buildAndFail();

			assertThat(theEvents(result).forTask(":compile"), hasItem(allOf(failure(), compilationOf("foo.cpp"))));
		}

		@Test
		void emitsEveryWorkItemWhenConfigurationCacheIsReused() {
			build.rootProject(withWorkListener());
			runner.withArguments(":compile", "--rerun").build();

			BuildResult result = runner.withArguments(":compile", "--rerun").build();

			assertThat(result.getOutput(), containsString("Reusing configuration cache"));
			assertThat(theEvents(result).forTask(":compile"), expectedWorkItems());
		}

		@Test
		void emitsNothingWithoutListener() {
			BuildResult result = runner.withArguments(":compile").build();

			assertThat(theEvents(result), empty());
		}

		@Test
		void warnsWhenRegisteredServiceIsNotListener() {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					abstract class NotAListener implements BuildService<BuildServiceParameters.None> {}
					gradle.sharedServices.registerIfAbsent(WorkCompletionListener.SERVICE_NAME, NotAListener) {}
				"""));
			});

			BuildResult result = runner.withArguments(":compile").build();

			assertThat(result.getOutput(), containsString("The build service registered as 'nokeeWorkCompletionListener' does not implement dev.nokee.work.WorkCompletionListener, "
				+ "so task ':compile' cannot emit work completion events. "
				+ "See https://github.com/nokeedev/nokee-companion/blob/main/src/main/java/dev/nokee/work/WorkCompletionListener.java for an example."));
		}

		@Test
		void emitsEveryWorkItemWithPerSourceOptions() {
			build.rootProject(withWorkListener());
			build.rootProject(withPerSourceOptions());

			BuildResult result = runner.withArguments(":compile").build();

			assertThat(theEvents(result).forTask(":compile"), containsInAnyOrder(
				allOf(compilationOf("foo.cpp"), withArgument("-DFOO_BUCKET")),
				allOf(compilationOf("main.cpp"), not(withArgument("-DFOO_BUCKET")))
			));
		}

		@Test
		void emitsFailedWorkItemWithPerSourceOptions() throws IOException {
			build.rootProject(withWorkListener());
			build.rootProject(withPerSourceOptions());
			Files.writeString(build.getLocation().resolve("src/main/cpp/foo.cpp"), "int foo( { return 42; }\n");

			BuildResult result = runner.withArguments(":compile").buildAndFail();

			assertThat(theEvents(result).forTask(":compile"), hasItem(allOf(failure(), compilationOf("foo.cpp"), withArgument("-DFOO_BUCKET"))));
		}
	}
}
