package dev.nokee.companion;

import dev.nokee.commons.fixtures.GradleProject;
import dev.nokee.commons.fixtures.GradleTaskUnderTest;
import dev.nokee.commons.fixtures.SourceOptionsAwareFunctionalTester;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.templates.CppApp;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;

import java.io.IOException;
import java.nio.file.Files;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.syntax.Syntax.*;

@GradleTaskUnderTest(":compile")
class CppCompileTaskFunctionalTests implements AbstractNativeLanguageCompilationFunctionalTester, AbstractNativeLanguageIncrementalCompilationFunctionalTester, AbstractNativeLanguageCachingCompilationFunctionalTester, SourceOptionsAwareFunctionalTester, AbstractNativeLanguageHeaderDiscoveryFunctionalTester, AbstractNativeLanguageIncrementalCompilationAfterFailureFunctionalTester {
	@GradleProject("project-without-source")
	public static GradleBuildElement makeEmptyProject() throws IOException {
		GradleBuildElement result = GradleBuildElement.empty();
		result.getBuildFile().plugins(it -> it.id("dev.nokee.native-companion"));
		result.getBuildFile().append(apply(plugin(NativeComponentPlugin.class)));
		result.getBuildFile().append(importClass("dev.nokee.language.cpp.tasks.CppCompile"));
		result.getBuildFile().append(staticImportClass(DefaultNativePlatform.class));
		result.getBuildFile().append(groovyDsl("""
				project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

				def compileTask = tasks.create("compile", CppCompile.clazz())
				compileTask.targetPlatform = host()
				compileTask.toolChain = extensions.getByType(NativeToolChainRegistry).getByName('clang')
				compileTask.objectFileDir = layout.buildDirectory.dir('objs')

				def subject = tasks.named('compile', CppCompile)
			""".stripIndent()));

		Files.writeString(result.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");

		return result;
	}

	@GradleProject("project-with-failing-source-files")
	public static GradleBuildElement makeProjectWithFailingSourceFiles() throws IOException {
		GradleBuildElement result = makeProjectWithSourceFiles();
		result.getBuildFile().append(groovyDsl("""
				compileTask.source('broken.cpp')
			""".stripIndent()));

		Files.writeString(result.file("broken.cpp"), "broken!");

		return result;
	}

	@GradleProject("project-with-sources")
	public static GradleBuildElement makeProjectWithSourceFiles() throws IOException {
		GradleBuildElement result = makeEmptyProject();
		result.getBuildFile().append(groovyDsl("""
				compileTask.source(fileTree('src/main/cpp'))
				compileTask.includes.from('src/main/headers', 'src/main/public')
			""".stripIndent()));

		new CppApp().writeToProject(result.getLocation());

		return result;
	}

	@GradleProject("project-with-removable-sources")
	public static GradleBuildElement makeProjectWithRemovableSourceFiles() throws IOException {
		GradleBuildElement result = makeEmptyProject();
		result.getBuildFile().append(groovyDsl("""
				compileTask.source(fileTree('src/main/cpp'))
				compileTask.includes.from('src/main/headers', 'src/main/public')
			""".stripIndent()));

		new CppApp().writeToProject(result.getLocation());

		Files.writeString(result.file("src/main/cpp/file-to-remove.cpp"), """
			int foobar() { return 42; }
		""".stripIndent());

		return result;
	}

	@GradleProject("project-with-source-options")
	public static GradleBuildElement makeProjectWithSourceOptions() throws IOException {
		GradleBuildElement build = makeProjectWithSourceFiles();
		build.getBuildFile().append(groovyDsl("""
			def file1 = 'src/main/cpp/message.cpp'
			def file2 = 'src/main/cpp/join.cpp'
			def file3 = 'src/main/cpp/split.cpp'
			def file4 = 'src/main/cpp/add.cpp'
			def missingSourceBucket = { it.compilerArgs.add('-DOTHER_MACRO') } as Action
			def moreSourceOptions = { it.compilerArgs.add('-DMORE_MACROS') } as Action

			compileTask.source(file1) { compilerArgs.add('-DMY_MACRO') }
		""".stripIndent()));

		return build;
	}

	@GradleProject("project-with-source-options-on-generated-source")
	public static GradleBuildElement makeProjectWithGeneratedSource() throws IOException {
		GradleBuildElement result = makeProjectWithSourceOptions();
		result.getBuildFile().append(groovyDsl("""
			abstract class GeneratorTask extends DefaultTask {
				@OutputFile
				abstract RegularFileProperty getOutputFile()

				@TaskAction
				void doGenerate() {
					outputFile.get().asFile.text = '''
						int foobar() { return 42; }

						#ifndef MY_MACRO
						# error "need the macro"
						#endif
					'''
				}
			}
			def generateTask = tasks.register('generator', GeneratorTask) {
				outputFile = layout.buildDirectory.file('foo.cpp')
			}

			compileTask.source(generateTask) { compilerArgs.add('-DMY_MACRO') }
			""".stripIndent()));

		return result;
	}

	@GradleProject("project-with-generated-source-options-on-static-source")
	public static GradleBuildElement makeProjectWithGeneratedSourceOptions() throws IOException {
		GradleBuildElement result = makeProjectWithSourceOptions();
		Files.writeString(result.file("my-other-source.cpp"), """
			#ifndef MY_MACRO
			#  error "need macros"
			#endif
			#ifndef MY_OTHER_MACRO
			#  error "need other macros"
			#endif
		""".stripIndent());
		result.getBuildFile().append(groovyDsl("""
			abstract class ArgGeneratorTask extends DefaultTask {
				@OutputFile
				abstract RegularFileProperty getOutputFile()

				@Internal
				Provider<List<String>> getArgs() {
					return outputFile.asFile.map { it.readLines().collect { it.trim() } }
				}

				@TaskAction
				void doGenerate() {
					outputFile.get().asFile.text = '''
						-DMY_MACRO
						-DMY_OTHER_MACRO
					'''
				}
			}
			def argTask = tasks.register('args', ArgGeneratorTask) {
				outputFile = layout.buildDirectory.file('args.txt')
			}

			compileTask.source('my-other-source.cpp') { compilerArgs.addAll(argTask.flatMap { it.args }) }
		""".stripIndent()));

		return result;
	}

	@GradleProject("project-with-generated-source-options-on-generated-source")
	public static GradleBuildElement makeProjectWithGeneratedSourceWithGeneratedSourceArgs() throws IOException {
		GradleBuildElement result = makeProjectWithSourceOptions();
		result.getBuildFile().append(groovyDsl("""
			abstract class ArgGeneratorTask extends DefaultTask {
				@OutputFile
				abstract RegularFileProperty getOutputFile()

				@Internal
				Provider<List<String>> getArgs() {
					return outputFile.asFile.map { it.readLines().collect { it.trim() } }
				}

				@TaskAction
				void doGenerate() {
					outputFile.get().asFile.text = '''
						-DMY_MACRO
						-DMY_OTHER_MACRO
					'''
				}
			}
			def argTask = tasks.register('args', ArgGeneratorTask) {
				outputFile = layout.buildDirectory.file('args.txt')
			}


			abstract class GeneratorTask extends DefaultTask {
				@OutputFile
				abstract RegularFileProperty getOutputFile()

				@TaskAction
				void doGenerate() {
					outputFile.get().asFile.text = '''
						int foobar() { return 42; }

						#ifndef MY_MACRO
						#  error "need macros"
						#endif
						#ifndef MY_OTHER_MACRO
						#  error "need other macros"
						#endif
					'''
				}
			}
			def generateTask = tasks.register('generator', GeneratorTask) {
				outputFile = layout.buildDirectory.file('foo.cpp')
			}

			compileTask.source(generateTask) {
				compilerArgs.addAll(argTask.flatMap { it.args })
			}
			""".stripIndent()));
		return result;
	}

	@GradleProject("project-with-include-macros")
	public static GradleBuildElement makeProjectWithIncludeMacros() throws IOException {
		GradleBuildElement result = makeProjectWithSourceFiles();
		Files.writeString(result.file("src/main/cpp/source-with-include-macros.cpp"), """
				#include INCLUDE_MACRO
				int foo() { return RETURN_VALUE; }
		""".stripIndent());
		Files.writeString(result.file("src/main/headers/my-include-macro.h"), """
			#pragma once
			#define RETURN_VALUE 42
			int foo();
		""".stripIndent());

		result.getBuildFile().append(groovyDsl("""
			compileTask.options.preprocessorOptions.define('INCLUDE_MACRO', '"my-include-macro.h"')
		""".stripIndent()));
		return result;
	}
}
