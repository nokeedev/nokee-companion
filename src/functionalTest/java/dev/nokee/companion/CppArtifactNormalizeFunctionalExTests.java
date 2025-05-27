package dev.nokee.companion;

import dev.gradleplugins.buildscript.io.GradleBuildFile;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.templates.CppApp;
import dev.nokee.templates.CppListLib;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.gradleplugins.runnerkit.GradleExecutor.gradleTestKit;

class CppArtifactNormalizeFunctionalExTests {
	GradleBuildElement build;
	@TempDir Path testDirectory;
	GradleRunner runner;

	GradleBuildFile app;
	GradleBuildFile lib;
	GradleBuildFile vendor;

	private void dependsOnVendorProject() {
		app.append(groovyDsl("""
			dependencies {
				implementation project(':vendor')
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation project(':vendor')
			}
		"""));
	}

	@BeforeEach
	void setup() throws IOException {
		System.out.println("Test Directory: " + testDirectory);

		build = GradleBuildElement.inDirectory(testDirectory);
		Files.writeString(build.file("gradle.properties"), "dev.nokee.native-companion.all-features.enabled=true");
		runner = GradleRunner.create(gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().forwardOutput();
		build.getSettingsFile().append(groovyDsl("rootProject.name = 'test'"));
//		build.getBuildFile().plugins(it -> {
//			it.id("dev.nokee.native-companion");
//		});
		build.getSettingsFile().append(groovyDsl("""
			include 'app', 'lib', 'vendor'
		"""));

		app = GradleBuildFile.inDirectory(build.dir("app"));
		lib = GradleBuildFile.inDirectory(build.dir("lib"));
		vendor = GradleBuildFile.inDirectory(build.dir("vendor"));

		app.plugins(it -> it.id("cpp-application"));

		lib.plugins(it -> it.id("cpp-library"));
		lib.append(groovyDsl("""
//			library.linkage = [Linkage.SHARED, Linkage.STATIC]
		"""));

//		runner.withTasks("build").build();
//		runner.withTasks(":app:dependencyInsight", "--configuration", "nativeLinkDebug", "--dependency", "utils").build();
		build.getBuildFile().append(groovyDsl("""
			import org.gradle.api.artifacts.transform.InputArtifact;
			import org.gradle.api.artifacts.transform.TransformAction;
			import org.gradle.api.artifacts.transform.TransformOutputs;
			import org.gradle.api.artifacts.transform.TransformParameters;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.swift.tasks.internal.SymbolHider;

	interface Param extends TransformParameters {
		@Internal
		Property<String> getDisplayName()
	}

	public abstract class IdentityTransform implements TransformAction<Param> {

		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Override
		public void transform(TransformOutputs outputs) {
			File input = getInputArtifact().get().getAsFile();
			String display = parameters.displayName.getOrElse('')
			System.out.println("{$display} IDENTITY " + input);
			if (input.isDirectory()) {
				outputs.dir(input);
			} else if (input.isFile()) {
				outputs.file(input);
			} else {
				throw new RuntimeException("Expecting a file or a directory: " + input.getAbsolutePath());
			}
		}
	}

	public abstract class RemoveArtifactTransform implements TransformAction<Param> {

		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Override
		public void transform(TransformOutputs outputs) {
			// no infered headers
			String display = parameters.displayName.getOrElse('')
			println("{$display}" + 'nullify artifact for ' + inputArtifact.get().asFile)
		}
	}

	abstract class UnpackObjectFiles implements TransformAction<TransformParameters.None> {
		@InputArtifact
		public abstract Provider<FileSystemLocation> getInputArtifact();

		@Override
		public void transform(TransformOutputs outputs) {
			System.out.println("UNPACK OBJECTS " + getInputArtifact().get().getAsFile());
			try {
				Files.walkFileTree(getInputArtifact().get().getAsFile().toPath(), new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						outputs.file(file);
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	abstract class UnexportSymbolsTransform implements TransformAction<TransformParameters.None> {
		@InputArtifact
	  	@PathSensitive(PathSensitivity.NAME_ONLY)
		public abstract Provider<FileSystemLocation> getInputArtifact();

//		@Inject
//		abstract InputChanges getInputChanges();

		@Inject
		abstract ExecOperations getExecOperations();

		@Override
		public void transform(TransformOutputs outputs) {
			System.out.println("UNEXPORT OBJECTS " + getInputArtifact().get().getAsFile());
//			unexport(getInputChanges(), outputs);
			unexportMainSymbol(inputArtifact.get().asFile, outputs)
		}

//		protected void unexport(InputChanges inputChanges, TransformOutputs outputs) {
//			for (FileChange change : inputChanges.getFileChanges(inputArtifact)) {
//				if (change.getChangeType() == ChangeType.REMOVED) {
//					File relocatedFileLocation = relocatedObject(change.getFile(), outputs);
//					relocatedFileLocation.delete();
//				} else {
//					if (change.getFile().isFile()) {
//						unexportMainSymbol(change.getFile(), outputs);
//					}
//				}
//			}
//		}

    private void unexportMainSymbol(File object, TransformOutputs outputs) {
        final File relocatedObject = relocatedObject(object, outputs);
        if (OperatingSystem.current().isWindows()) {
            try {
                final SymbolHider symbolHider = new SymbolHider(object);
                symbolHider.hideSymbol("main");     // 64 bit
                symbolHider.hideSymbol("_main");    // 32 bit
                symbolHider.hideSymbol("wmain");    // 64 bit
                symbolHider.hideSymbol("_wmain");   // 32 bit
                symbolHider.saveTo(relocatedObject);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            execOperations.exec(new Action<ExecSpec>() {
                @Override
                public void execute(ExecSpec execSpec) {
                    // TODO: should use target platform to make this decision
                    if (OperatingSystem.current().isMacOsX()) {
                        execSpec.executable("ld"); // TODO: Locate this tool from a tool provider
                        execSpec.args(object);
                        execSpec.args("-o", relocatedObject);
                        execSpec.args("-r"); // relink, produce another object file
                        execSpec.args("-unexported_symbol", "_main"); // hide _main symbol
                    } else if (OperatingSystem.current().isLinux()) {
                        execSpec.executable("objcopy"); // TODO: Locate this tool from a tool provider
                        execSpec.args("-L", "main"); // hide main symbol
                        execSpec.args(object);
                        execSpec.args(relocatedObject);
                    } else {
                        throw new IllegalStateException("Do not know how to unexport a main symbol on " + OperatingSystem.current());
                    }
                }
            });
        }
        }

		private File relocatedObject(File object, TransformOutputs outputs) {
			return outputs.file(object.getName());
		}
	}

//	abstract class UsageCompatibility implements AttributeCompatibilityRule<Usage> {
//		@Override
//		public void execute(CompatibilityCheckDetails<Usage> details) {
//            Usage consumerValue = (Usage)details.getConsumerValue();
//            Usage producerValue = (Usage)details.getProducerValue();
//            if (consumerValue == null) {
//                details.compatible();
//            } else if (consumerValue.getName().equals("cplus-plus-")) {
//                if (COMPATIBLE_WITH_JAVA_API.contains(producerValue.getName())) {
//                    details.compatible();
//                }
//
//            } else if (consumerValue.getName().equals("java-runtime") && producerValue.getName().equals("java-runtime-jars")) {
//                details.compatible();
//            }
//		}
//	}

	abstract class LibElementsObjectNativeLinkDisam implements AttributeDisambiguationRule<LibraryElements> {
		final def nativeLinkStaticPackaging = Boolean.parseBoolean(System.getProperty("dev.nokee.native.link-static-packaging", "true"));

		void execute(MultipleCandidatesDetails<TargetJvmEnvironment> details) {
			println 'wat? ' + details.candidateValues
			println(details.candidateValues.collect { it.name } as Set == ['objects', 'link-archive'] as Set)
			def values = details.candidateValues.collectEntries {
				[(it.name): it]
			}
			if (values.keySet() == ['objects', 'link-archive'] as Set) {
				if (nativeLinkStaticPackaging) {
					details.closestMatch(values.get('link-archive'))
				} else {
					details.closestMatch(values.get('objects'))
				}
			}
		}
	}

	abstract class ArtTypesLinkable implements AttributeCompatibilityRule<String> {
		void execute(CompatibilityCheckDetails<String> details) {
			String consumerValue = details.getConsumerValue();
			String producerValue = details.getProducerValue();
			println("$consumerValue ==>> $producerValue")
			if (consumerValue == null) {
			   details.compatible();
			} else if (consumerValue.equals("dev.nokee.linkable-objects")) {
				if (Arrays.asList("dylib", "a", "public.object-code", "so").contains(producerValue)) {
			   		details.compatible();
			   	} else {
			   		// this may be a particular case of .so.1.2.0
			   		//   Gradle will select the last "extension" as the artifact type which we check to see if it's an int
	    			try {
			        	Integer.parseInt(producerValue);
			        	details.compatible();
			     	} catch (NumberFormatException nfe) {
			        	// not compatible
			     	}
			   	}
			} else if (Arrays.asList("o", "obj", "public.object-code").contains(consumerValue)) {
				if (Arrays.asList("o", "obj", "public.object-code").contains(producerValue)) {
					details.compatible()
				} else if (producerValue.equals("dev.nokee.linkable-objects")) {
					details.compatible()
				}
			} else if (consumerValue.equals("a") && producerValue.equals("public-object-code")) {
				details.compatible()
			} else if (consumerValue.equals("dev.nokee.runnable-objects")) {
				if (Arrays.asList("dylib", "so").contains(producerValue)) {
			   		details.compatible();
			   	} else {
			   		// this may be a particular case of .so.1.2.0
			   		//   Gradle will select the last "extension" as the artifact type which we check to see if it's an int
	    			try {
			        	Integer.parseInt(producerValue);
			        	details.compatible();
			     	} catch (NumberFormatException nfe) {
			        	// not compatible
			     	}
			   	}

		   	// Because of core C++ that declare type directly on configuration attribute
			} else if (consumerValue.equals('public.c-plus-plus-header-directory')) {
				if (producerValue.equals('directory')) {
					details.compatible();
				}
			}
		}
	}

			subprojects {
				if (name == 'vendor') return;

				configurations.create('linkAndRuntimeOnly') {
					canBeConsumed = false
					canBeResolved = false
				}
				configurations.create('linkOnly') {
					canBeConsumed = false
					canBeResolved = false
				}
				configurations.create('runtimeOnly') {
					canBeConsumed = false
					canBeResolved = false
				}
				components.withType(CppBinary).configureEach {
					if (it instanceof CppTestExecutable) return;
					def qualifyingName = (name - 'main').uncapitalize()
					configurations.named("nativeLink${qualifyingName.capitalize()}") {
						extendsFrom(configurations.getByName('linkAndRuntimeOnly'))
						extendsFrom(configurations.getByName('linkOnly'))
					}
					configurations.named("nativeRuntime${qualifyingName.capitalize()}") {
						extendsFrom(configurations.getByName('linkAndRuntimeOnly'))
						extendsFrom(configurations.getByName('runtimeOnly'))
					}
				}


plugins.withType(CppBasePlugin) {
println 'rewire headers'
				components.withType(CppLibrary).configureEach {
					configurations.named("cppApiElements") {
						outgoing {
							artifacts.all {
							println 'WAT???? ' + it.type
								it.type = 'public.c-plus-plus-header-directory'
							println 'WAT???? ' + it.type
							}
						}
					}
				}
				afterEvaluate {
				components.withType(CppComponent).configureEach { component ->
					binaries.configureEach {
						def qualifyingName = (name - 'main' - 'Executable').uncapitalize()
						configurations.named("cppCompile${qualifyingName.capitalize()}") {
							attributes {
								attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, 'public.c-plus-plus-header-directory')
							}
						}
						compileTask.get().includes.setFrom([])
						compileTask.get().includes.from(configurations."cppCompile${qualifyingName.capitalize()}")
						compileTask.get().includes.from(component.privateHeaderDirs)
					}
				}
				}
}


				components.withType(CppBinary).configureEach {
					def qualifyingName = (name - 'main' - 'Executable').uncapitalize()
					configurations.named("cppCompile${qualifyingName.capitalize()}") {
						attributes {
							attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						}
					}
					configurations.named("nativeLink${qualifyingName.capitalize()}") {
						attributes {
							attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						}
					}
					configurations.named("nativeRuntime${qualifyingName.capitalize()}") {
						attributes {
							attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						}
					}
				}


				dependencies.attributesSchema.attributeDisambiguationPrecedence(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
				dependencies.attributesSchema.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).disambiguationRules.add(LibElementsObjectNativeLinkDisam)

				dependencies.attributesSchema.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE).compatibilityRules.add(ArtTypesLinkable)

				components.withType(CppBinary).configureEach {
					def qualifyingName = (name - 'main' - 'Executable').uncapitalize()
					configurations.named("nativeLink${qualifyingName.capitalize()}") {
						attributes {
							attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.linkable-objects")
						}
					}
					configurations.named("nativeRuntime${qualifyingName.capitalize()}") {
						attributes {
							attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.runnable-objects")
						}
					}
				}


				println 'config ' + name
				dependencies.attributesSchema.attribute(Usage.USAGE_ATTRIBUTE)
				dependencies.attributesSchema.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)
				dependencies.attributesSchema.attribute(CppBinary.LINKAGE_ATTRIBUTE)
				dependencies.artifactTypes.all { println("new artifact type ${it.name}") }


				// DYLIB & SO
					dependencies.registerTransform(IdentityTransform) {
						from
							.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
							.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
						to
							.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
							.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
						parameters {
							displayName = 'native-runtime -> native-link & dynamic-lib -> dynamic-lib'
						}
					}

//					dependencies.registerTransform(RemoveArtifactTransform) {
//						from
//							.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
//							.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
//						to
//							.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
//							.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.HEADERS_CPLUSPLUS))
//					}
				///

				// DYLIB
				dependencies.artifactTypes.create('dylib').attributes
					.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
					.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
//				dependencies.registerTransform(IdentityTransform) {
//					from
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dylib")
//					to
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dylib")
//				}

				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dylib")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.c-plus-plus-header-directory")
					parameters {
						displayName = 'native-runtime -> C++ API & dylib -> c++ header'
					}
				}
				///////

				// SO
				dependencies.artifactTypes.create('so')
					.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
					.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
//				dependencies.registerTransform(IdentityTransform) {
//					from
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "so")
//					to
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "so")
//				}

				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "so")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.c-plus-plus-header-directory")
					parameters {
						displayName = 'native-runtime -> C++ API & so -> c++ header'
					}
				}
				/////////

//				dependencies.registerTransform(RemoveArtifactTransform) {
//					from
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
//					to
//						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
//					parameters {
//						displayName = 'native-link -> native-runtime (discard)'
//					}
//				}

				// A
				dependencies.artifactTypes.create('a').attributes
					.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.LINK_ARCHIVE))
					.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.STATIC)
				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.LINK_ARCHIVE))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "a")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.HEADERS_CPLUSPLUS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.c-plus-plus-header-directory")
					parameters {
						displayName = 'native-link -> C++ API & a -> C++ header'
					}
				}

				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.LINK_ARCHIVE))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "a")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.LINK_ARCHIVE))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.runnable-objects")
					parameters {
						displayName = 'native-link -> native-runtime & a -> runnable-objects'
					}
				}
				////////


				dependencies.registerTransform(UnexportSymbolsTransform) {
					from
//						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						.attribute(Attribute.of('testable', String), "no")
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
					to
//						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						.attribute(Attribute.of('testable', String), "yes")
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
//					parameters {
//						displayName = 'native-link -> native-runtime & a -> a'
//					}
				}


				// OBJ
				dependencies.artifactTypes.create('o').attributes
					.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))

				dependencies.artifactTypes.create('obj').attributes
					.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
					.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))

				dependencies.registerTransform(UnpackObjectFiles) {
					from
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code-directory")
					to
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")//"dev.nokee.linkable-objects")//"public.object-code")
				}

				// NORMALIZE OBJECT DIRECTORIES

				// normalize *nix object file
				dependencies.registerTransform(IdentityTransform) {
					from
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "o") // compatible with obj
					to
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
					parameters {
						displayName = '[normalize] o -> object-code'
					}
				}

//				// normalize win object file
//				dependencies.registerTransform(IdentityTransform) {
//					from
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "obj")
//					to
//						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
//					parameters {
//						displayName = '[normalize] obj -> object-code'
//					}
//				}
				//////

				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.C_PLUS_PLUS_API))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.HEADERS_CPLUSPLUS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.c-plus-plus-header-directory")
					parameters {
						displayName = 'native-link -> c++ api (discard)'
					}
				}

				dependencies.registerTransform(RemoveArtifactTransform) {
					from
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "public.object-code")
					to
						.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "dev.nokee.runnable-objects")
					parameters {
						displayName = 'native-link -> c++ api (discard)'
					}
				}

				tasks.register("verify") {
					doLast {
						println 'compile'
						configurations.getByName('cppCompileDebug').files.each {
							println it
						}
						println 'runtime'
						configurations.getByName('nativeRuntimeDebug').files.each {
							println it
						}
						println 'link'
						configurations.getByName('nativeLinkDebug').files.each {
							println it
						}
					}
			   }
			}
		"""));

	}

	@Test
	void blah() {
		dependsOnVendorProject();

		build.file("vendor/libfoo.dylib");
		vendor.append(groovyDsl("""
			configurations.create('runtimeElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					artifact(project.file('libfoo.dylib'))
				}
			}
		"""));
		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blah__dylib_file() {
		build.file("vendor/libfoo.dylib");
		app.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.dylib')
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.dylib')
			}
		"""));
		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahz() {
		dependsOnVendorProject();

		build.file("vendor/libfoo.so");
		vendor.append(groovyDsl("""
			configurations.create('runtimeElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					artifact(project.file('libfoo.so'))
				}
			}
		"""));
		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahz__so_file() {
		build.file("vendor/libfoo.so");
		app.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.so')
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.so')
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahz_version_suffix() {
		dependsOnVendorProject();

		build.file("vendor/libfoo.so.1.2.0");
		vendor.append(groovyDsl("""
			configurations.create('runtimeElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					variants.create("versioned-file") {
						attributes {
							attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
							attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
						}
						artifact(project.file('libfoo.so.1.2.0')) { type = 'so' }
					}
				}
			}
		"""));
		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahz_version_suffix_file() {
		build.file("vendor/libfoo.so.1.2.0");

//		build.getBuildFile().append(groovyDsl("""
//				subprojects {
//					if (name == 'vendor') return;
//
//					dependencies.artifactTypes.create('so.1.2.0').attributes
//						.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_RUNTIME))
//						.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
//						.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
//				}
//			"""));

		// MUST USE THE CORRECT BUCKET, there is no way to know what is the file type
		app.append(groovyDsl("""
			dependencies {
				linkAndRuntimeOnly files('../vendor/libfoo.so.1.2.0')
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				linkAndRuntimeOnly files('../vendor/libfoo.so.1.2.0')
			}
		"""));


		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahzz() {
		dependsOnVendorProject();

		build.file("vendor/libfoo.a");
		vendor.append(groovyDsl("""
			configurations.create('linkElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					artifact(project.file('libfoo.a'))
				}
			}
		"""));
		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void blahzz__a_archive_file() {
		build.file("vendor/libfoo.a");
		app.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.a')
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/libfoo.a')
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}



	@Test
	void expose_object_files() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		vendor.append(groovyDsl("""
			configurations.create('linkElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					artifact(project.file('obj-dir/a.o'))
					artifact(project.file('obj-dir/b.o'))
					artifact(project.file('obj-dir/c.o'))
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void expose_object_files_as_dep() {
		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		app.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/obj-dir').asFileTree
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation files('../vendor/obj-dir').asFileTree
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void expose_object_files_win() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.obj");
		build.file("vendor/obj-dir/b.obj");
		build.file("vendor/obj-dir/c.obj");
		vendor.append(groovyDsl("""
			configurations.create('linkElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					artifact(project.file('obj-dir/a.obj'))
					artifact(project.file('obj-dir/b.obj'))
					artifact(project.file('obj-dir/c.obj'))
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void expose_object_files_explicit() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.obj");
		build.file("vendor/obj-dir/c.o");
		vendor.append(groovyDsl("""
			configurations.create('linkElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					variants.create('objects') {
						attributes {
							attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
							attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						}
						artifact(project.file('obj-dir/a.o'))
						artifact(project.file('obj-dir/b.obj')) { type = 'public.object-code' }
						artifact(project.file('obj-dir/c.o')) { type = 'public.object-code' }
					}
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void expose_object_dir() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		vendor.append(groovyDsl("""
			configurations.create('linkElements') {
				canBeConsumed = true
				canBeResolved = false
				attributes {
//					attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
//					attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
					attribute(Attribute.of('foo', String), 'bar')
				}
				outgoing {
					variants.create('objects') {
						attributes {
							attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.NATIVE_LINK))
							attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
						}
						artifact(project.file('obj-dir')) { type = 'public.object-code-directory' }
					}
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}


	@Test
	void objects_prefer_staticlib() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		vendor.plugins(it -> it.id("cpp-library"));
		vendor.append(groovyDsl("""
			library {
				linkage = [Linkage.STATIC]
				binaries.configureEach {
					def qualifyingName = (name - 'main').uncapitalize()
					configurations.named("${qualifyingName}LinkElements") {
						outgoing.variants.create('objects') {
							attributes {
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
							}
							artifact(project.file('obj-dir')) { type = 'public.object-code-directory' }
						}
					}
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void objects_change_default_to_prefer_objects() {
		dependsOnVendorProject();

		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		vendor.plugins(it -> it.id("cpp-library"));
		vendor.append(groovyDsl("""
			library {
				linkage = [Linkage.STATIC]
				binaries.configureEach {
					def qualifyingName = (name - 'main').uncapitalize()
					configurations.named("${qualifyingName}LinkElements") {
						outgoing.variants.create('objects') {
							attributes {
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
							}
							artifact(project.file('obj-dir')) { type = 'public.object-code-directory' }
						}
					}
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify", "-Ddev.nokee.native.link-static-packaging=false").build();

		throw new RuntimeException("not correct result");
	}

	@Test
	void objects_choose_objs() {
		build.file("vendor/obj-dir/a.o");
		build.file("vendor/obj-dir/b.o");
		build.file("vendor/obj-dir/c.o");
		vendor.plugins(it -> it.id("cpp-library"));
		vendor.append(groovyDsl("""
			library {
				linkage = [Linkage.STATIC]
				binaries.configureEach {
					def qualifyingName = (name - 'main').uncapitalize()
					configurations.named("${qualifyingName}LinkElements") {
						outgoing.variants.create('objects') {
							attributes {
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
							}
							artifact(project.file('obj-dir')) { type = 'public.object-code-directory' }
						}
					}
				}
			}
		"""));

		app.append(groovyDsl("""
			dependencies {
				implementation(project(':vendor')) {
					attributes {
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
					}
				}
			}
		"""));
		lib.append(groovyDsl("""
			dependencies {
				implementation(project(':vendor')) {
					attributes {
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
					}
				}
			}
		"""));

		runner.withTasks(":vendor:outgoingVariants").build();
		runner.withTasks("verify").build();
	}

	@Test
	void test_against_objects() {
		new CppApp().writeToProject(build.dir("app"));
		new CppListLib().writeToProject(build.dir("lib"));
		app.append(groovyDsl("""
			configurations.create("cppApiElements") {
				canBeResolved = false
				canBeConsumed = true
				attributes {
					attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.C_PLUS_PLUS_API))
					attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
//					attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.HEADERS_CPLUSPLUS))
//					attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
				}
				outgoing {
//					artifact(compileTask.flatMap { it.objectFilesDir }) {
//						type = 'public.object-code-directory'
//					}
				}
			}
			components.withType(CppExecutable).configureEach {
				def qualifyingName = (name - 'main').uncapitalize()
				configurations.named("${qualifyingName}RuntimeElements") {
					attributes {
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, "application"))
					}
				}
				configurations.create("${qualifyingName}TestableLinkElements") {
					canBeResolved = false
					canBeConsumed = true
					attributes {
						attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_LINK))
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
					}
					outgoing {
						variants.create('objects') {
							attributes {
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
								attribute(Attribute.of("testable", String), "no")
							}
							artifact(compileTask.flatMap { it.objectFileDir }) {
								type = 'public.object-code-directory'
							}
						}
					}
				}
				configurations.create("${qualifyingName}TestableRuntimeElements") {
					canBeResolved = false
					canBeConsumed = true
					attributes {
						attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_RUNTIME))
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
					}
					outgoing {
						variants.create('objects') {
							attributes {
								attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
								attribute(Attribute.of("testable", String), "no")
							}
//							artifact(compileTask.flatMap { it.objectFileDir }) {
//								type = 'public.object-code-directory'
//							}
						}
					}
				}
			}

			components.withType(CppTestExecutable).configureEach {
				def qualifyingName = (name - 'Executable').uncapitalize()
				configurations.named("nativeLink${qualifyingName.capitalize()}") {
					attributes {
						attribute(Attribute.of("testable", String), "yes")
					}
				}
			}

			dependencies {
				testImplementation(project) {
					attributes {
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
					}
				}
			}

			tasks.named("verify") {
				dependsOn {
					[configurations.getByName('nativeLinkTest')]
				}
				doLast {
					println '=============='
					println 'compile'
					configurations.getByName('cppCompileTest').files.each {
						println it
					}
					println 'runtime'
					configurations.getByName('nativeRuntimeTest').files.each {
						println it
					}
					println 'link'
					configurations.getByName('nativeLinkTest').files.each {
						println it
					}
				}
		   }
		"""));
		app.plugins(it -> it.id("cpp-unit-test"));
		lib.plugins(it -> it.id("cpp-unit-test"));
		lib.append(groovyDsl("""
			components.withType(CppSharedLibrary).configureEach {
				def qualifyingName = (name - 'main').uncapitalize()
				configurations.named("${qualifyingName}LinkElements") {
					attributes {
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.DYNAMIC_LIB))
					}
				}
				configurations.create("${qualifyingName}TestableLinkElements") {
					canBeResolved = false
					canBeConsumed = true
					attributes {
						attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_LINK))
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
						attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
						attribute(Attribute.of("testable", String), "yes")
						attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
						attribute(CppBinary.DEBUGGABLE_ATTRIBUTE, debuggable)
						attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, targetPlatform.targetMachine.architecture)
						attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, targetPlatform.targetMachine.operatingSystemFamily)
					}
					outgoing {
//						variants.create('objects') {
//							attributes {
//							}
							artifact(compileTask.flatMap { it.objectFileDir }) {
								type = 'public.object-code-directory'
							}
//						}
					}
				}
				configurations.create("${qualifyingName}TestableRuntimeElements") {
					canBeResolved = false
					canBeConsumed = true
					attributes {
						attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_RUNTIME))
						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
						attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
						attribute(Attribute.of("testable", String), "yes")
						attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
						attribute(CppBinary.DEBUGGABLE_ATTRIBUTE, debuggable)
						attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, targetPlatform.targetMachine.architecture)
						attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, targetPlatform.targetMachine.operatingSystemFamily)
					}
					outgoing {
//						variants.create('objects') {
//							attributes {
//							}
//							artifact(compileTask.flatMap { it.objectFileDir }) {
//								type = 'public.object-code-directory'
//							}
//						}
					}
				}
//				configurations.create("${qualifyingName}TestableLinkElements") {
//					canBeResolved = false
//					canBeConsumed = true
//					attributes {
//						attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
//						attribute(CppBinary.OPTIMIZED_ATTRIBUTE, optimized)
//
//						attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.NATIVE_LINK))
//						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.OBJECTS))
//						attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED)
//						attribute(Attribute.of('testable', String), 'yes')
//						attribute(CppBinary.DEBUGGABLE_ATTRIBUTE, debuggable)
//						attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, targetPlatform.targetMachine.architecture)
//						attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, targetPlatform.targetMachine.operatingSystemFamily)
//					}
//					outgoing {
//						artifact(compileTask.flatMap { it.objectFileDir }) {
//							type = 'public.object-code-directory'
//						}
//					}
//				}
			}

			components.withType(CppTestExecutable).configureEach {
				def qualifyingName = (name - 'Executable').uncapitalize()
				configurations.named("nativeLink${qualifyingName.capitalize()}") {
					attributes {
						attribute(Attribute.of("testable", String), "yes")
					}
				}
				configurations.named("nativeRuntime${qualifyingName.capitalize()}") {
					attributes {
						attribute(Attribute.of("testable", String), "yes")
					}
				}
			}

			dependencies {
				testImplementation(project) {
					attributes {
						attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.OBJECTS))
					}
				}
			}

			tasks.named("verify") {
				dependsOn {
					[configurations.getByName('nativeLinkTest')]
				}
				doLast {
					println '=============='
					println 'compile'
					configurations.getByName('cppCompileTest').files.each {
						println it
					}
					println 'runtime'
					configurations.getByName('nativeRuntimeTest').files.each {
						println it
					}
					println 'link'
					configurations.getByName('nativeLinkTest').files.each {
						println it
					}
				}
		   }
		"""));

		runner.withTasks(":lib:outgoingVariants").build();
		runner.publishBuildScans().withTasks("verify").build();
	}
}
