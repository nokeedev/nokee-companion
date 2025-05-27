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
			import static dev.nokee.companion.CppBinaryProperties.*

			import static dev.nokee.companion.util.CopyFromAction.copyFrom

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

						configurations.register(CppNames.of(binary).configurationName('linkTestableObjectsElements').toString().replace("debug", "coverage")) {
							canBeConsumed = true
							canBeResolved = false
							extendsFrom = configurations.getByName(linkElementsConfigurationName(binary)).extendsFrom
							attributes {
								attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_LINK))
								attributeProvider(CppBinary.DEBUGGABLE_ATTRIBUTE, provider(debuggabilityOf(binary)))
								attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, provider(optimizationOf(binary)))
								if (component instanceof CppLibrary) {
									attribute(CppBinary.LINKAGE_ATTRIBUTE, binary instanceof CppSharedLibrary ? Linkage.SHARED : Linkage.STATIC)
								}
								attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, binary.targetMachine.architecture)
								attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, binary.targetMachine.operatingSystemFamily)
								attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, 'objects'))
								attribute(Attribute.of("coverage", String.class), "yes");
								attribute(Attribute.of("testable", String.class), "no");
								attribute(Attribute.of("internal-local-to-project-name", String.class), project.path);
							}
							outgoing {
								def t = tasks.register(CppNames.of(binary).taskName { it.forObject('coverageObjects') }.toString(), Sync) {
									from(coverageTask.map { ObjectFiles.of(it) })
									into(layout.buildDirectory.dir("tmp/${name}"))
									eachFile { println it }
								}
								artifact(t) {
									type = directoryType(OBJECT_CODE_TYPE)
								}
							}
						}
					}
				}
			}

			components.withType(CppTestExecutable).configureEach {
				if (toolChain instanceof GccCompatibleToolChain) {
					ext.optimized = linkTask.flatMap { it.linkerArgs }.map { !it.contains('--coverage') }
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
