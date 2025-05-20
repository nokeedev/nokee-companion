package dev.nokee.companion.fixtures;

import dev.gradleplugins.buildscript.io.GradleBuildFile;
import dev.gradleplugins.buildscript.io.GradleSettingsFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoverageObjectMockPluginFixture {
	public String pluginId() {
		return "nokee-testing.gcov-test";
	}

	public void writeToDirectory(Path directory) {
		try {
			_writeToDirectory(directory);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public String asGroovyScript() {
		return """
			import dev.nokee.commons.names.CppNames
			import static dev.nokee.commons.names.CppNames.*
			import dev.nokee.commons.names.NamingScheme
			import dev.nokee.language.cpp.tasks.CppCompile
			import dev.nokee.companion.ObjectFiles
			import static dev.nokee.companion.NativeArtifactTypeDefinition.*
			import static dev.nokee.companion.CppBinaryObjects.*

			import static dev.nokee.companion.util.CopyFromAction.copyFrom

			def linkArtifact(CppComponent component, CppBinary binary, TaskProvider coverageTask) {
				if (component instanceof CppLibrary) {
					return tasks.register(CppNames.of(binary).taskName('sync').toString().replace('Debug', 'Coverage'), Sync) {
						from(coverageTask.map { ObjectFiles.of(it) })
						into(layout.buildDirectory.dir("tmp/${name}"))
					}
				} else {
					return tasks.register(CppNames.of(binary).taskName('relocateMain').toString().replace('Debug', 'Coverage'), UnexportMainSymbol) {
						objects.from(coverageTask.map { ObjectFiles.of(it) })
			   			outputDirectory = layout.buildDirectory.dir("tmp/${name}")
					}
				}
			}

			components.withType(ProductionCppComponent).configureEach { component ->
				binaries.configureEach { binary ->
					if (!optimized && toolChain instanceof GccCompatibleToolChain) {
						def names = CppNames.of(binary)//.with('buildTypeName', 'coverage')
						def coverageTask = tasks.register(names.taskName('compile', 'cpp').toString().replace('Debug', 'Coverage'), CppCompile.clazz())
						coverageTask.configure(copyFrom(compileTask))
						coverageTask.configure {
							objectFileDir = layout.buildDirectory.dir("obj/" + names.toString(NamingScheme.dirNames()).replace('debug', 'coverage'))
							compilerArgs.add('-coverage')
						}

						def linkArtifact = linkArtifact(component, binary, coverageTask)

						testable.elements.create('coverage-objects') {
							cppApiElements.configure {
								outgoing {
									artifact(tasks.register(names.taskName('sync', 'headers').toString(), Sync) {
										from(component instanceof CppLibrary ? publicHeaderFiles : headerFiles)
										into(layout.buildDirectory.dir("tmp/${name}"))
									}) { type = directoryType(C_PLUS_PLUS_HEADER_TYPE) }
								}
							}
							linkElements.configure {
								outgoing {
									artifact(linkArtifact) { type = directoryType(OBJECT_CODE_TYPE) }
								}
							}
						}
					}
				}
			}
			""";
	}

	private void _writeToDirectory(Path directory) throws IOException {
		Path projectDir = Files.createDirectories(directory.resolve("gradle/plugins/gcov-plugins"));
		GradleBuildFile.inDirectory(projectDir).plugins(it -> it.id("groovy-gradle-plugin"));
		GradleSettingsFile.inDirectory(projectDir);
		Files.writeString(Files.createDirectories(projectDir.resolve("src/main/groovy")).resolve("nokee-testing.gcov-test.gradle"), asGroovyScript());
	}
}
