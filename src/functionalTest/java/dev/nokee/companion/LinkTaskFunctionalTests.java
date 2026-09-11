package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.elements.core.SourceFile;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.nokee.companion.fixtures.GradleRunnerProperties.forConfigurationCacheEnabled;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

class LinkTaskFunctionalTests {
	private static final String EXPORT_DEFINES = """
		#if defined(_WIN32) || defined(__CYGWIN__)
		  #if defined(MYLIB_BUILD)
		    #define MYLIB_EXPORT __declspec(dllexport)
		  #else
		    #define MYLIB_EXPORT __declspec(dllimport)
		  #endif
		#elif defined(__GNUC__) || defined(__clang__)
		  #define MYLIB_EXPORT __attribute__((visibility("default")))
		#else
		  #define MYLIB_EXPORT
		#endif
		""";

	GradleBuild build;
	GradleRunner runner;
	GradleRunnerArguments args = GradleRunnerArguments.create().withInfoLogging();

	@BeforeEach
	void setup(@TempDir Path testDirectory) throws IOException {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.properties(it -> {
			it.putAll(forConfigurationCacheEnabled());
		});
		build.rootProject(project -> {
			project.append(staticImportClass(OperatingSystem.class));
			project.append(staticImportClass(DefaultNativePlatform.class));
			project.append(importClass(ToolType.class));
			project.append(importClass(NativeToolChainRegistry.class));
			project.append(apply(plugin(StandardToolChainsPlugin.class)));
			project.append(groovyDsl("""
				def toolChains = project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

				void sharedLib(String name) {
					def cppTask = tasks.register("compile${name.capitalize()}", CppCompile) {
						objectFileDir = layout.buildDirectory.dir("obj/$name")
						source.from(fileTree("src/$name/cpp"))
						includes.from("src/$name/headers")
						macros.put('MYLIB_BUILD', null)
					}

					def linkTask = tasks.register("link${name.capitalize()}", LinkSharedLibrary) {
						source.from(cppTask.flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName("out/$name/$name"))
						installName = linkedFile.locationOnly.map { it.asFile.name }
					}

					tasks.named { it == 'link' }.configureEach {
						libs.from(linkTask.flatMap { it.importLibrary.orElse(it.linkedFile) })
					}
				}

				def compileTask = tasks.register('compile', CppCompile) {
					objectFileDir = layout.buildDirectory.dir('obj/main')
					source.from(fileTree('src/main/cpp'))
					includes.from('src/main/headers')
				}

				tasks.withType(AbstractNativeCompileTask).configureEach {
					positionIndependentCode = true
					toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
					targetPlatform = host()
					systemIncludes.from(toolChain.zip(targetPlatform) { toolchain, platform -> toolchain.select(platform).getSystemLibraries(ToolType.CPP_COMPILER).includeDirs })
				}

				tasks.withType(AbstractLinkTask).configureEach {
					toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
					targetPlatform = host()
					debuggable = true
				}
			"""));
		});

		build.rootProject(project -> {
			project.plugins(it -> {
				it.id("dev.nokee.native-companion");
				it.id("lifecycle-base");
			});
		});
	}

	@Nested
	class LinkExecutableTests extends LinkTaskTester {
		@BeforeEach
		void setup() {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.register('link', Class.forName('%s')) {
						source.from(tasks.named('compile').flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getExecutableName('out/main/main'))
					}
				""".formatted("dev.nokee.nativeplatform.tasks.LinkExecutableTask")));
			});
		}
	}

	@Nested
	class LinkSharedLibraryTests extends LinkTaskTester {
		@BeforeEach
		void setup() {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.register('link', Class.forName('%s')) {
						source.from(tasks.named('compile').flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName('out/main/main'))

						if (!targetPlatform.get().operatingSystem.isMacOsX()) {
							installName = linkedFile.locationOnly.map { it.asFile.name }
						}
					}
				""".formatted("dev.nokee.nativeplatform.tasks.LinkSharedLibraryTask")));
			});
		}
	}

	abstract class LinkTaskTester {
		@Test
		void doesNotRelinkWhenIncomingLibraryMovesToAnotherAbsoluteLocation() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('linkFoo') {
						def filename = it.linkedFile.locationOnly.get().asFile.name
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName("out/alternate/${filename}"))
					}
				"""));
			});

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())).task(":link"), upToDate());
		}

		@Test
		void relinkWhenIncomingLibraryChangeName() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('linkFoo') {
						installName = current().getSharedLibraryName('foo')
					}
				"""));
			});

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('linkFoo') {
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName('out/foo/alternate'))
						installName = current().getSharedLibraryName('foo')
					}
				"""));
			});

			// TODO: Theoretically, only MSVC actually care about the DLL name as it encodes it into the import table
			//   Other toolchain uses soname or installName
			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenIncomingLibraryChangeRelativeLocation() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						outputs.cacheIf { true }
					}
				"""));
			});

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						libs.setFrom(fileTree('build/out') { include(current().getSharedLibraryName('foo/foo')) }.builtBy('linkFoo'))
					}
				"""));
			});

			assertThat(runs(runner.withArguments(args.withInfoLogging().withTasks(":link").toList())).task(":link"), upToDate());
		}
	}

	private Consumer<? super GradleBuild.GradleProject> sharedLibComponent(String name) {
		return project -> {
			project.append(groovyDsl("sharedLib('%s')".formatted(name)));
		};
	}

	private static class Fixture {
		public void writeToProject(GradleBuild build) {
			build.rootProject(project -> {
				SourceFile.of("impl.cpp", EXPORT_DEFINES + "MYLIB_EXPORT int greet() { return 32; }")
					.writeToDirectory(project.file("src/foo/cpp"));
				SourceFile.of("main.cpp", EXPORT_DEFINES + """
					MYLIB_EXPORT int greet();
					int main() {
						return greet() == 32 ? 0 : 1;
					}
					""").writeToDirectory(project.file("src/main/cpp"));
			});
		}
	}
}
