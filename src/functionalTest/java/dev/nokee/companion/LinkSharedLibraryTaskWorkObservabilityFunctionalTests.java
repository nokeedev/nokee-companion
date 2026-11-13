package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.WorkObservabilityFixtures.ObservedWorkItem;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.internal.os.OperatingSystem;
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

class LinkSharedLibraryTaskWorkObservabilityFunctionalTests {
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
			});
			project.append(importClass("dev.nokee.language.cpp.tasks.CppCompile"));
			project.append(importClass("dev.nokee.nativeplatform.tasks.LinkTask"));
			project.append(importClass("dev.nokee.work.WorkCompletionListener"));
			project.append(importClass(BuildService.class));
			project.append(importClass(BuildServiceParameters.class));
			project.append(importClass(NativeToolChainRegistry.class));
			project.append(importClass(ToolType.class));
			project.append(staticImportClass(DefaultNativePlatform.class));
			project.append(staticImportClass(OperatingSystem.class));
			project.append(apply(plugin(StandardToolChainsPlugin.class)));
			project.append(groovyDsl("""
				def toolChains = project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

				def compileTask = tasks.register('compile', CppCompile.clazz()) {
					positionIndependentCode = true
					toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
					targetPlatform = host()
					systemIncludes.from(toolChain.zip(targetPlatform) { toolchain, platform -> toolchain.select(platform).getSystemLibraries(ToolType.CPP_COMPILER).includeDirs })
					objectFileDir = layout.buildDirectory.dir('obj/main')
					source.from(fileTree('src/main/cpp'))
				}

				tasks.register('link', LinkTask.forSharedLibrary()) {
					toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
					targetPlatform = host()
					debuggable = true
					source.from(compileTask.flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
					linkedFile = layout.buildDirectory.file(current().getSharedLibraryName('out/main'))
				}
			"""));
		});
		build.rootProject(withWorkItemLines());

		Files.createDirectories(build.getLocation().resolve("src/main/cpp"));
		Files.writeString(build.getLocation().resolve("src/main/cpp/foo.cpp"), "int foo() { return 42; }\n");
		Files.writeString(build.getLocation().resolve("src/main/cpp/main.cpp"), "int foo();\nint main() { return foo() - 42; }\n");
	}

	static Matcher<Iterable<? extends ObservedWorkItem>> expectedWorkItems() {
		return contains(linkOf(OperatingSystem.current().getSharedLibraryName("build/out/main")));
	}

	@Nested
	class WorkPlanTests {
		@BeforeEach
		void registerPrintWorkPlanTask() {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					abstract class PrintWorkPlan extends DefaultTask {
						@Input abstract Property<LinkTask.WorkItem> getWorkPlan()

						@TaskAction
						void print() {
							println "WORK PLAN ${WorkItemLines.of(workPlan.get())}"
						}
					}

					tasks.register('printWorkPlan', PrintWorkPlan) {
						workPlan = tasks.named('link', LinkTask).flatMap { it.workPlan }
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
			runner.withArguments(":link", "printWorkPlan").build();

			BuildResult result = runner.withArguments(":link", "printWorkPlan").build();

			assertThat(from(result).task(":link"), upToDate());
			assertThat(theWorkPlan(result), expectedWorkItems());
		}

		@Test
		void consumingWorkPlanRunsInputProducersButNotTheTask() {
			BuildResult result = runner.withArguments("printWorkPlan").build();

			assertThat(from(result), tasksExecuted(allOf(hasItem(":compile"), not(hasItem(":link")))));
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

			BuildResult result = runner.withArguments(":link").build();

			assertThat(theEvents(result).forTask(":link"), expectedWorkItems());
		}

		@Test
		void emitsNothingWhenUpToDate() {
			build.rootProject(withWorkListener());
			runner.withArguments(":link").build();

			BuildResult result = runner.withArguments(":link").build();

			assertThat(theEvents(result).forTask(":link"), empty());
		}

		@Test
		void emitsFailedWorkItem() {
			build.rootProject(withWorkListener());
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link', LinkTask) {
						linkerArgs.add(file('missing.o').absolutePath)
					}
				"""));
			});

			BuildResult result = runner.withArguments(":link").buildAndFail();

			assertThat(theEvents(result).forTask(":link"), hasItem(allOf(failure(), linkOf(OperatingSystem.current().getSharedLibraryName("build/out/main")))));
		}

		@Test
		void emitsEveryWorkItemWhenConfigurationCacheIsReused() {
			build.rootProject(withWorkListener());
			runner.withArguments(":link", "--rerun").build();

			BuildResult result = runner.withArguments(":link", "--rerun").build();

			assertThat(result.getOutput(), containsString("Reusing configuration cache"));
			assertThat(theEvents(result).forTask(":link"), expectedWorkItems());
		}

		@Test
		void emitsNothingWithoutListener() {
			BuildResult result = runner.withArguments(":link").build();

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

			BuildResult result = runner.withArguments(":link").build();

			assertThat(result.getOutput(), containsString("The build service registered as 'nokeeWorkCompletionListener' does not implement dev.nokee.work.WorkCompletionListener, "
				+ "so task ':link' cannot emit work completion events. "
				+ "See https://github.com/nokeedev/nokee-companion/blob/main/src/main/java/dev/nokee/work/WorkCompletionListener.java for an example."));
		}
	}
}
