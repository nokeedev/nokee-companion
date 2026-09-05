package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.templates.CppApp;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static org.hamcrest.MatcherAssert.assertThat;

class CppCompileTaskConfigurationCacheFunctionalTests {
	GradleBuild build;
	GradleRunner runner;
	GradleRunnerArguments args = GradleRunnerArguments.create().withTasks(":compile");

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		build = GradleBuild.inDirectory(testDirectory)
			.properties(it -> {
				it.put("dev.nokee.native-companion.all-features.enabled", true);
			})
			.rootProject(project -> {
				project.append(staticImportClass(DefaultNativePlatform.class));
				project.append(importClass(NativeToolChainRegistry.class));
				project.append(apply(plugin(StandardToolChainsPlugin.class)));
				project.plugins(it -> it.id("dev.nokee.native-companion"));
				project.append(apply(plugin(NativeComponentPlugin.class)));
				project.append(importClass("dev.nokee.language.cpp.tasks.CppCompile"));
				project.append(importClass(ToolType.class));
				project.append(groovyDsl("""
						def toolChains = project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

						def compileTask = tasks.register("compile", CppCompile.clazz()) {
							targetPlatform = host()
							toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
							objectFileDir = layout.buildDirectory.dir('objs')
							source.from(fileTree('src/main/cpp'))
							includes.from('src/main/headers')
							systemIncludes.from(toolChain.zip(targetPlatform) { toolchain, platform -> toolchain.select(platform).getSystemLibraries(ToolType.CPP_COMPILER).includeDirs })
						}
					""".stripIndent()));

				new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(project.getLocation());
			});
		runner = GradleRunner.create().forwardOutput().withProjectDir(build.getLocation().toFile()).withPluginClasspath();
	}

	@Test
	void staysUpToDateWhenUsingConfigurationCacheOnFullyUpToDateTask() {
		assertThat(theBuild(runner.withArguments(args.toList())), becomesUpToDate());
		assertThat(succeeds(runner.withArguments(args.withConfigurationCacheEnabled().withInfoLogging().toList())).task(":compile"), upToDate());
	}
}
