package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.companion.fixtures.GradleRunnerArguments;
import dev.nokee.elements.core.*;
import dev.nokee.elements.nativebase.NativeElement;
import dev.nokee.elements.nativebase.NativeLibraryElement;
import org.apache.commons.lang3.SystemUtils;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.testkit.runner.GradleRunner;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.nokee.companion.fixtures.GradleRunnerArguments.forTasks;
import static dev.nokee.companion.fixtures.GradleRunnerProperties.forConfigurationCacheEnabled;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static dev.nokee.companion.fixtures.PathExtensions.write;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static dev.nokee.elements.nativebase.NativeSourceElement.ofSources;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinkAvoidanceFunctionalTests {
	GradleBuild build;
	GradleRunner runner;
	GradleRunnerArguments args = GradleRunnerArguments.create().withInfoLogging();

	@BeforeEach
	void setup(@TempDir Path testDirectory) throws IOException {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.properties(it -> {
			it.putAll(forConfigurationCacheEnabled());
			it.put("dev.nokee.native-companion.link-avoidance.enabled", true);
		});
		build.rootProject(project -> {
			project.append(importClass("dev.nokee.nativeplatform.tasks.LinkAbiAware.AbiSnapshotter"));
			project.append(staticImportClass(OperatingSystem.class));
			project.append(staticImportClass(DefaultNativePlatform.class));
			project.append(importClass(DefaultNativePlatform.class));
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
					}

					tasks.named { it == 'link' }.configureEach {
						libs.from(linkTask.flatMap { it.importLibrary.orElse(it.linkedFile) })
					}
				}

				void staticLib(String name) {
					def cppTask = tasks.register("compile${name.capitalize()}", CppCompile) {
						objectFileDir = layout.buildDirectory.dir("obj/$name")
						source.from(fileTree("src/$name/cpp"))
						includes.from("src/$name/headers")
						macros.put('MYLIB_BUILD', null)
					}

					def createTask = tasks.register("create${name.capitalize()}", CreateStaticLibrary) {
						source.from(cppTask.flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						outputFile = layout.buildDirectory.file(current().getStaticLibraryName("out/$name/$name"))
						toolChain = targetPlatform.map { toolChains.getForPlatform(it) }
						targetPlatform = host()
					}

					tasks.named { it == 'link' }.configureEach {
						libs.from(createTask.flatMap { it.outputFile })
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
					debuggable = true
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

	private static SourceFileElement addedSymbol() {
		return new SourceFileElement() {
			@Override
			public SourceFile getSourceFile() {
				return sourceFile("impl2.cpp", Fixture.EXPORT_DEFINES + """
						MYLIB_EXPORT int foo() { return 32; }
					""");
			}
		};
	}

	private static class AvoidOnNarrowOnly implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI relink", "AbiSnapshotter.FULL_ABI", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
			);
		}
	}

	private static class AvoidOnLinkAbiAndUp implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
			);
		}
	}

	private static class StSizeParticularity implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			if (SystemUtils.IS_OS_LINUX) {
				return Stream.of(
					Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
					Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksExecutedAndNotSkipped(hasItem(":link"))),
					Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksExecutedAndNotSkipped(hasItem(":link")))
				);
			} else if (SystemUtils.IS_OS_WINDOWS) {
				return Stream.of(
					Arguments.argumentSet("no ABI does not relink", "AbiSnapshotter.NONE", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
				);
			}
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
			);
		}
	}

	private static class ImportLibraryParticularity implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			if (SystemUtils.IS_OS_WINDOWS) {
				return Stream.of(
					Arguments.argumentSet("no ABI does not relink", "AbiSnapshotter.NONE", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
				);
			}
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
			);
		}
	}

	private static class AvoidOnNarrowOnlyOrWindows implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			if (SystemUtils.IS_OS_WINDOWS) {
				return Stream.of(
					Arguments.argumentSet("no ABI does not relink", "AbiSnapshotter.NONE", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksSkipped(hasItem(":link"))),
					Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
				);
			}
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI relink", "AbiSnapshotter.FULL_ABI", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksSkipped(hasItem(":link")))
			);
		}
	}

	private static class AlwaysRelink implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			return Stream.of(
				Arguments.argumentSet("no ABI relinks", "AbiSnapshotter.NONE", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("full ABI does not relink", "AbiSnapshotter.FULL_ABI", tasksExecutedAndNotSkipped(hasItem(":link"))),
				Arguments.argumentSet("narrow ABI does not relink", "AbiSnapshotter.NARROW_ABI", tasksExecutedAndNotSkipped(hasItem(":link")))
			);
		}
	}

	@Nested
	class LinkExecutableTests extends LinkAvoidanceTester {
		@BeforeEach
		void setup() {
			build.rootProject(project -> {
				project.append(staticImportClass(OperatingSystem.class));
				project.append(groovyDsl("""
					tasks.register('link', Class.forName('%s')) {
						source.from(tasks.named('compile').flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getExecutableName('out/main/main'))
					}
				""".formatted("dev.nokee.nativeplatform.tasks.LinkExecutableTask")));
			});
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenExportedSymbolSizeChangesForNonPositionIndependentExecutable(String linkAbi, Matcher<ExecutedBuild> matcher) {
			assumeTrue(SystemUtils.IS_OS_LINUX, "copy relocations are an ELF concept"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// st_size is its own field in Elf64_Sym, apart from the name and from the binding and type packed
			// into st_info, so growing an exported data object leaves all three untouched. It reaches a consumer
			// only through a copy relocation: non-PIC code computes the object's address into a register straight
			// from the instruction stream, so the linker has to hand it an address it knows at link time. It does
			// that by reserving st_size bytes in the consumer's own .bss, pointing the code there, and leaving an
			// R_*_COPY for the loader to memcpy the contents across at startup. That reservation is what freezes
			// the size into the link result. Nothing fails at link time; an unrelinked consumer shows up at run
			// time, as the loader reporting that the symbol "has different size in shared object".
			//
			// The reservation exists because the consumer's code cannot reach the library's storage at all. The
			// address is encoded in the instruction bits and has to be final when the linker writes the file,
			// while the library's base address is not chosen until load time. Patching the instructions later
			// would need writable code pages, and a linker does not rewrite a direct address computation into a
			// GOT load, so the only lever left is to put the object where the linker already knows the address.
			//
			// The copy leaves one live object rather than two, the loader throwing a switch as it goes. Left
			// alone, the library reads and writes its own storage. Once a consumer takes a copy, the consumer
			// comes first in the global symbol lookup order, so the loader fills the library's own GOT slot with
			// the consumer's address and both sides work on the consumer's .bss while the library's storage goes
			// unused. The R_*_COPY carries the library's initializer in before main runs, and it carries only as
			// many bytes as the consumer reserved. A stale size therefore causes no divergence between the two:
			// it leaves the library believing the object is larger than the reservation it now writes into, so
			// library code walks off the end into whatever follows in the consumer's .bss.
			//
			// This case belongs to LinkExecutableTests rather than the shared tester because it cannot be made to
			// happen for a shared library. A linker refuses to build one out of non-PIC objects at all, rejecting
			// every relocation "against symbol X which may bind externally", so a shared library is PIC by
			// construction, reads the object through the GOT, and never takes a copy. The fixture compiles with
			// positionIndependentCode = true, which is why this test has to turn it off.
			growableBuffer();
			build.rootProject(project -> project.append(groovyDsl("""
				tasks.named('compile') { positionIndependentCode = false }
				tasks.named('link') { linkerArgs.add('-no-pie') }
			""")));

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			// Rebuilt by the same compiler from the same source but for a larger object, so the exported name,
			// its binding and its type are all identical and st_size is the only difference.
			SourceFile.of("impl2.cpp", "char my_buffer[128] = {};").writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

	}

	@Nested
	class LinkSharedLibraryTests extends LinkAvoidanceTester {
		@BeforeEach
		void setup() {
			build.rootProject( project -> {
				project.append(groovyDsl("""
					tasks.register('link', Class.forName('%s')) {
						source.from(tasks.named('compile').flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName('out/main/main'))

						if (!targetPlatform.get().operatingSystem.isMacOsX()) {
							installName = linkedFile.locationOnly.map { it.asFile.name }
						}
//						installName = targetPlatform.zip(linkedFile.locationOnly.map { it.asFile.name }) { (platform, installName) -> platform.operatingSystem.isMacOsX() ? null : installName }
					}
				""".formatted("dev.nokee.nativeplatform.tasks.LinkSharedLibraryTask")));
			});
		}
	}

	private static Consumer<GradleBuild.GradleProject> writeToProject(NativeElement element) {
		return project -> {
			new GradleLayoutElement().applyTo(ofMain(element)).writeToDirectory(project.getLocation());
		};
	}

	abstract class LinkAvoidanceTester {
		@ParameterizedTest
		@ArgumentsSource(ImportLibraryParticularity.class)
		void doesNotRelinkOnImplementationOnlyChange(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withImplementationOnlyChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenNewExportedSymbol(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			addedSymbol().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnNarrowOnly.class)
		void whenExportedSymbolNotUsedByConsumerIsAdded(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			// The consumer imports only greet(); an exported symbol it never references is not in the
			// narrowed ABI, so adding one leaves the consumer's snapshot unchanged.
			fixture.lib.impl.withUnusedExportedSymbol().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnNarrowOnly.class)
		void whenExportedSymbolNotUsedByConsumerChangesAbi(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			fixture.lib.impl.withUnusedExportedSymbol().writeToDirectory(fooComponent());
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			// Changing the ABI of an exported symbol the consumer does not import (unused()'s signature) is
			// absent from the narrowed ABI, so it must not relink.
			fixture.lib.impl.withUnusedExportedSymbolAbiChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnNarrowOnly.class)
		void whenExportedSymbolNotUsedByConsumerIsRemoved(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			fixture.lib.impl.withUnusedExportedSymbol().writeToDirectory(fooComponent());
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			// Removing an exported symbol the consumer does not import leaves the narrowed ABI unchanged.
			fixture.lib.impl.writeToDirectory(fooComponent()); // back to only greet()

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(ImportLibraryParticularity.class)
		void whenStaticFunctionAdded(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// A static function has internal linkage, i.e. private to its compilation unit, so it never
			// reaches the exported symbol table and adding one must not change the ABI seen by consumers.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withAddedStaticFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnLinkAbiAndUp.class)
		void whenStaticVariableAdded(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// A static variable has internal linkage, i.e. private to its compilation unit, so it never
			// reaches the exported symbol table and adding one must not change the ABI seen by consumers.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withAddedStaticVariable().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(ImportLibraryParticularity.class)
		void whenAnonymousNamespaceFunctionAdded(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// A function in an anonymous namespace also has internal linkage (a mangled, LOCAL symbol),
			// so - like a static function - it stays out of the exported symbol table and must not relink.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withAddedAnonymousNamespaceFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnNarrowOnlyOrWindows.class)
		void whenUnusedInlineFunctionAdded(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// An inline function keeps external linkage and is emitted as a weak (COMDAT) exported symbol, so
			// under a whole-ABI snapshot it would relink. But it lives only in the library's implementation:
			// the consumer has no declaration of it and never imports it, so narrowing drops it from the
			// consumer's ABI and adding one must not relink. (An inline symbol the consumer sees would be
			// emitted as the consumer's own weak copy, not imported either — so it never relinks the consumer.)
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withAddedInlineFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@Test
		void alwaysRelinkAfterClean() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			assertThat(runs(runner.withArguments(args.withTasks(":clean", ":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenRemovedExportedSymbol(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withRenamedAbiChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenSymbolStrongnessTransition(String linkAbi, Matcher<ExecutedBuild> matcher) {
			assumeFalse(SystemUtils.IS_OS_WINDOWS, "Weak symbols require GCC/Clang"); // TODO: assert toolchain capability not OS
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withWeakSymbolChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		Path fooComponent() {
			return build.getLocation().resolve("src/foo/cpp");
		}

		Path mainComponent() {
			return build.getLocation().resolve("src/main/cpp");
		}

		void growableBuffer() {
			SourceFile.of("impl2.cpp", "char my_buffer[64] = {};").writeToDirectory(fooComponent());
			SourceFile.of("main.cpp", """
					#include <cstdio>
					extern char my_buffer[];
					int main() {
						my_buffer[0] = 'H';
						my_buffer[1] = 'i';
						my_buffer[2] = '\\0';

						std::puts(my_buffer);
						return 0;
					}
				""").writeToDirectory(mainComponent());
			build.rootProject(sharedLibComponent("foo"));
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class) // TODO: Should not relink on Link ABI as we should not care about st-size for PIC
		void whenExportedSymbolSizeChangesForPositionIndependentConsumer(String linkAbi, Matcher<ExecutedBuild> matcher) {
			assumeTrue(SystemUtils.IS_OS_LINUX, "copy relocations are an ELF concept"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// PIC code loads an object's address out of the GOT, which the loader fills in, so nothing about the
			// object has to be known at link time. No .bss reservation is made, no copy relocation is emitted and
			// my_buffer stays an UND entry of size 0, which leaves the library's st_size out of the link result
			// entirely. This holds for both link kinds: a shared library is PIC by construction, and an executable
			// built from PIC objects behaves the same way whether or not it is linked -pie.
			growableBuffer();

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			SourceFile.of("impl2.cpp", "char my_buffer[128] = {};").writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenSymbolTypeChangesFromFunctionToVariable(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withVariableKindChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);

			// TODO: Replace with ExportedSymbolEx().asVariable()
//			fixture.lib.api.withVariableKindChange().writeToDirectory(build.getLocation().resolve("includes"));
			fixture.app.main.useAsVariableSymbol().writeToDirectory(mainComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);

			// TODO: SEEMS TO BE ONLY UNDEFINED
//			fixture.lib.api.writeToDirectory(build.getLocation().resolve("includes")); // Return to original
			fixture.lib.impl.writeToDirectory(fooComponent()); // Return to original
			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenParameterCountChanges(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.addParameterChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(ImportLibraryParticularity.class) // TODO: Linux is up-to-date on all -> is this the ABI itself that use the same assembly languages?
		void whenReturnTypeChanges(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withReturnTypeChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(StSizeParticularity.class) // Theorically, it should be AvoidOnLinkAbiAndUp
		//  The problems comes from the fact that we only need to capture the symbol st_size in ELF format
		//  when linking non-PIE binaries. It's quite hard to determine this so, for now, we will accept over
		//  relinks for correctness.
		void whenFunctionBecomesVariableInC(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withVariableKindChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);

			// TODO: Replace with ExportedSymbolEx().asVariable()
//			fixture.lib.api.withVariableKindChange().writeToDirectory(build.getLocation().resolve("include"));
			fixture.app.main.useAsVariableSymbol().writeToDirectory(mainComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));

			fixture.lib.impl.writeToDirectory(fooComponent()); // Return to original
			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnLinkAbiAndUp.class)
		void whenParameterCountChangesInC(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.addParameterChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AvoidOnLinkAbiAndUp.class)
		void whenReturnTypeChangesInC(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withReturnTypeChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ValueSource(strings = { "AbiSnapshotter.NONE", "AbiSnapshotter.FULL_ABI", "AbiSnapshotter.NARROW_ABI" })
		void whenLibraryChangeLocationButNotAbi(String linkAbi) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('linkFoo', LinkSharedLibrary) {
						installName = linkedFile.get().asFile.name // use non-absolute default value
					}
				"""));
			});

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			// relocating a library should not cause a relink
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('linkFoo', LinkSharedLibrary) {
						linkedFile = layout.buildDirectory.file(linkedFile.get().asFile.name) // safe-ish as we are just building one variant
					}
				"""));
			});

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenStaticLibraryImplementationChanges(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(staticLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			fixture.lib.impl.withImplementationOnlyChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenLibraryTargetsAnotherMachine(String linkAbi, Matcher<ExecutedBuild> matcher) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// The target machine - e_machine in ELF, cputype/cpusubtype in Mach-O, Machine in the PE COFF
			// header - is not part of the exported symbol table, yet linking against a library built for
			// another machine is rejected by GNU ld, ld64 and link.exe alike. A library that keeps every one
			// of its exports but moves to another machine is therefore a different linker-facing
			// representation and must relink.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			build.rootProject(project -> {
				project.append(groovyDsl("""
					toolChains.withType(Clang) {
						target('macos:x86-64') {
							cppCompiler.withArguments {
								it.add('--target=x86_64-apple-darwin')
								return it
							}
							linker.withArguments {
								it.add('--target=x86_64-apple-darwin')
								return it
							}
						}
					}
					toolChains.withType(Gcc) {
						target('linux:i686') {
							cppCompiler.executable = 'i686-linux-gnu-g++-10'
							linker.executable = 'i686-linux-gnu-gcc-10'
						}
					}
					def platform = providers.gradleProperty('arch').map {
						def result = null
						if (host().operatingSystem.toFamilyName() == 'macos') {
							result = new DefaultNativePlatform("macos:x86-64")
							result.architecture('x86-64')
						} else if (host().operatingSystem.toFamilyName() == 'linux') {
							result = new DefaultNativePlatform("linux:i686")
							result.architecture('i686')
						} else if (host().operatingSystem.toFamilyName() == 'windows') {
							result = new DefaultNativePlatform("windows:x86")
							result.architecture('x86')
						}

						return result
					}.orElse(host())
					tasks.named('compileFoo') { targetPlatform = platform }
					tasks.named('linkFoo') { targetPlatform = platform }
				"""));
			});
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			assertThat(runs(runner.withArguments(":link", "-Parch=other")), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenLibraryTargetsAnotherOsAbi(String linkAbi, Matcher<ExecutedBuild> matcher) throws IOException {
			assumeTrue(SystemUtils.IS_OS_LINUX, "EI_OSABI only exists in ELF"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// EI_OSABI, e_ident byte 7, tells the linker which OS extensions the rest of the file may use, so
			// it too decides whether a link can succeed while living outside the exported symbol table. An
			// ifunc is the way to get a GNU/Linux OS ABI out of the toolchain rather than out of a byte
			// rewrite: the resolver is static, so the exported symbol table is unchanged.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));

			assertThat(theBuild(runner.withArguments(args.withTasks(":link").withInfoLogging().toList())), becomesUpToDate());

			// Unlike the machine, the OS ABI is not something a compiler flag asks for: it is emitted because
			// of what the library contains. Assert the toolchain actually moved it, so a toolchain that does
			// not fails here instead of silently turning this into a test of nothing.
			final long EI_OSABI = 7;
			final int ELFOSABI_FREEBSD = 9;
			try (RandomAccessFile f = new RandomAccessFile(build.getLocation().resolve(OperatingSystem.current().getSharedLibraryName("build/out/foo/foo")).toFile(), "rw")) {
				f.seek(EI_OSABI);
				f.write(ELFOSABI_FREEBSD);
			}

			assertThat(runs(runner.withArguments(args.withTasks(":link").append("-x", ":linkFoo").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenExportedSymbolSizeChanges(String linkAbi, Matcher<ExecutedBuild> matcher) { // TODO: Not sure if this is true
			assumeTrue(SystemUtils.IS_OS_LINUX, "st_size is an ELF concept"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// st_size is its own field in Elf64_Sym, apart from the name and from the binding and type packed
			// into st_info, so growing an exported data object leaves all three untouched. The linker reserves
			// st_size bytes in a non-PIE consumer's own .bss and emits a copy relocation, which is what makes
			// the size part of what the consumer was linked against. Nothing fails at link time: a consumer
			// left unrelinked shows up at run time instead, as the loader reporting that the symbol "has
			// different size in shared object".
			SourceFile.of("impl2.cpp", "char my_buffer[64] = {};").writeToDirectory(fooComponent());
			SourceFile.of("main.cpp", """
					#include <cstdio>
					extern char my_buffer[];
					int main() {
						my_buffer[0] = 'H';
						my_buffer[1] = 'i';
						my_buffer[2] = '\\0';

						std::puts(my_buffer);
						return 0;
					}
				""").writeToDirectory(mainComponent());
			build.rootProject(sharedLibComponent("foo"));

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			// Rebuilt by the same compiler from the same source but for a larger object, so the exported name,
			// its binding and its type are all identical and st_size is the only difference.
			SourceFile.of("impl2.cpp", "char my_buffer[128] = {};").writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenExportedSymbolBecomesThreadLocal(String linkAbi, Matcher<ExecutedBuild> matcher) {
			// TODO: Is this really just on Linux?
			assumeTrue(SystemUtils.IS_OS_LINUX, "STT_TLS is an ELF concept"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// Only the type nibble of st_info moves, from STT_OBJECT to STT_TLS, so the name and the binding
			// are identical across the two builds. Thread-local symbols use their own relocation family, so a
			// consumer holding an ordinary data relocation against this symbol stops being valid and GNU ld
			// rejects it with "accessed both as normal and thread local symbol".
			SourceFile.of("impl2.cpp", "int counter = 0;").writeToDirectory(fooComponent());
			SourceFile.of("main.cpp", """
					extern int counter;
					int main() {
						return ++counter;
					}
				""").writeToDirectory(mainComponent());
			build.rootProject(sharedLibComponent("foo"));

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			SourceFile.of("impl2.cpp", "__thread int counter = 0;").writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ArgumentsSource(AlwaysRelink.class)
		void whenExportedSymbolMovesToAnotherVersion(String linkAbi, Matcher<ExecutedBuild> matcher) {
			assumeTrue(SystemUtils.IS_OS_LINUX, "symbol versioning is a GNU extension to ELF"); // TODO: assert binary format not OS

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						linkAbi.linkAbiSnapshotting = %s
					}
				""".formatted(linkAbi)));
			});

			// A version script lets one name live at several versions. The linker binds a reference to the
			// default version and writes that version's name into the consumer's own .gnu.version_r, so the
			// consumer records greet@V1 rather than greet. Moving the symbol to V2 leaves .dynsym untouched -
			// same st_name, same st_info, same st_shndx - while every consumer's link result now names V2.
			// ElfBinaryHasher hashes .dynsym entries and reads .dynamic only far enough to find DT_SONAME, so
			// .gnu.version_d and .gnu.version reach neither half of ElfHashCode and the link stays up-to-date.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));

			// Two scripts rather than one edited in place: the version script is not a tracked input of the
			// link task, so switching which path linkerArgs names is what makes linkFoo itself rerun.
			write(build.getLocation().resolve("v1.map"), "V1 { global: *; };\n");
			write(build.getLocation().resolve("v2.map"), "V2 { global: *; };\n");
			build.rootProject(project -> project.append(groovyDsl("""
				tasks.named('linkFoo', LinkSharedLibrary) {
					linkerArgs.set(['-Wl,--version-script=' + file('v1.map').absolutePath])
				}
			""")));

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			build.rootProject(project -> project.append(groovyDsl("""
				tasks.named('linkFoo', LinkSharedLibrary) {
					linkerArgs.set(['-Wl,--version-script=' + file('v2.map').absolutePath])
				}
			""")));

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), matcher);
		}

		@ParameterizedTest
		@ValueSource(strings = { "AbiSnapshotter.NONE", "AbiSnapshotter.FULL_ABI", "AbiSnapshotter.NARROW_ABI"})
		void realizeTaskLibraryOnlyDuringExecutionPhase(String linkAbi) {
			build.rootProject(project -> {
				project.append(groovyDsl("""
				tasks.named('link') {
					linkAbi.linkAbiSnapshotting = %s
				}
			""".formatted(linkAbi)));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			build.subproject("other-lib", project -> {
				write(project.file("src/main/cpp/foo.cpp"), "int foo_bar() { return 42; }");
				project.plugins(it -> it.id("cpp-library"));
			});
			succeeds(runner.withArguments(":other-lib:assemble"));

			build.rootProject(project -> {
				project.append(groovyDsl("""
					tasks.named('link') {
						libs.from(providers.gradleProperty('additional-lib').orElse([]).map {
							println('resolving additional-lib: ' + it)
							return it
						})
					}
				"""));
			});

			assertThat(theBuild(runner.withArguments(forTasks(":link"))), becomesUpToDate());

			String sharedLibPath = OperatingSystem.current().getSharedLibraryName("other-lib/build/lib/main/debug/other-lib");
			ExecutedBuild result = runs(runner.withArguments(":link", "-Padditional-lib=" + sharedLibPath));
			assertThat(result, tasksExecuted(hasItem(":link")));
			assertThat(result.task(":link"), output(containsString("resolving additional-lib: " + sharedLibPath)));
		}
	}

	private Consumer<? super GradleBuild.GradleProject> sharedLibComponent(String name) {
		return project -> {
			project.append(groovyDsl("sharedLib('%s')".formatted(name)));
		};
	}

	private Consumer<? super GradleBuild.GradleProject> staticLibComponent(String name) {
		return project -> {
			project.append(groovyDsl("staticLib('%s')".formatted(name)));
		};
	}

	private static class Fixture {
		static final String EXPORT_DEFINES = """
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
		private enum SymbolKind { FUNCTION, VARIABLE }
		private final CppApp app;
		private final CppLib lib;

		public Fixture() {
			this(false);
		}

		public Fixture(boolean useExternC) {
			this.app = new CppApp(useExternC);
			this.lib = new CppLib(useExternC);
		}

		public Fixture usingExternC() {
			return new Fixture(true);
		}

		public class CppApp extends ProjectElement {
			public final CppMainUsingApiHeader main;

			public CppApp(boolean useExternC) {
				this.main = new CppMainUsingApiHeader(useExternC);
			}

			@Override
			public Element getMainElement() {
				return ofSources(SourceElement.ofElements(main, SourceFileElement.ofFile(sourceFile("other.cpp", "int foo() { return 45; }"))));
			}
		}

		public class CppLib extends ProjectElement {
			public final CppImpl impl;

			public CppLib(boolean useExternC) {
				this.impl = new CppImpl(useExternC);
			}

			@Override
			public NativeLibraryElement getMainElement() {
				return new NativeLibraryElement() {
					@Override
					public SourceElement getPublicHeaders() {
						return SourceElement.empty();
					}

					@Override
					public SourceElement getSources() {
						return impl;
					}
				};
			}
		}

		public void writeToProject(GradleBuild build) {
			build.rootProject(project -> {
				new GradleLayoutElement().applyTo(app).writeToDirectory(project.getLocation());
			});
			build.rootProject(project -> {
				lib.impl.writeToDirectory(project.getLocation().resolve("src/foo/cpp"));
			});
		}

		class CppImpl extends SourceFileElement {
			private final boolean useExternC;

			public CppImpl(boolean useExternC) {
				this.useExternC = useExternC;
			}

			@Override
			public SourceFile getSourceFile() {
				return sourceFile("impl.cpp", EXPORT_DEFINES + externC(" MYLIB_EXPORT int greet() { return 32; }"));
			}

			public SourceFileElement withImplementationOnlyChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("MYLIB_EXPORT int greet() { return 100; }")));
			}

			// The consumer only calls greet(), so unused() is a symbol the library exports but the consumer
			// never imports. Narrowing keeps only the imported symbols in the consumer's snapshot, so adding,
			// changing, or removing unused() must not relink the consumer. unused() has external linkage, so
			// it genuinely reaches the exported symbol table (the debug variant is unoptimized).
			public SourceFileElement withUnusedExportedSymbol() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet() { return 32; }") + "\nint unused() { return 7; }"));
			}

			// Changes unused()'s ABI (its signature, i.e. its exported symbol) while leaving greet() intact.
			public SourceFileElement withUnusedExportedSymbolAbiChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet() { return 32; }") + "\nint unused(int value) { return value; }"));
			}

			// The following changes add a symbol that is private to this compilation unit (internal
			// linkage). greet() references the added symbol so it genuinely lands in the object file
			// (the debug variant is unoptimized, so a referenced symbol is neither dead-stripped nor
			// inlined away), and greet() still returns 32 so the exported ABI is otherwise unchanged.
			// These are C++-only constructs, so they are never combined with extern "C".
			public SourceFileElement withAddedStaticFunction() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + "static int helper() { return 32; }\nMYLIB_EXPORT int greet() { return helper(); }"));
			}

			public SourceFileElement withAddedStaticVariable() {
				// volatile keeps the read (and thus the storage) even if the variant were ever optimized.
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + "static volatile int counter = 32;\nMYLIB_EXPORT int greet() { return counter; }"));
			}

			public SourceFileElement withAddedAnonymousNamespaceFunction() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + "namespace { int helper() { return 32; } }\nMYLIB_EXPORT int greet() { return helper(); }"));
			}

			// Unlike the above, an inline function keeps external linkage and is emitted as a weak
			// exported symbol, so adding it is expected to relink.
			public SourceFileElement withAddedInlineFunction() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + "inline int helper() { return 32; }\nMYLIB_EXPORT int greet() { return helper(); }"));
			}

			public SourceFileElement withRenamedAbiChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("MYLIB_EXPORT int greet_renamed() { return 32; }")));
			}

			public SourceFileElement withWeakSymbolChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("__attribute__((weak)) MYLIB_EXPORT int greet() { return 32; }")));
			}

			private String externC(String s) {
				return useExternC ? "extern \"C\" " + s : s;
			}

			public SourceFileElement withVariableKindChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("MYLIB_EXPORT int greet = 32;")));
			}

			public SourceFileElement addParameterChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("MYLIB_EXPORT int greet(int foo) { return foo + 32; }")));
			}

			public SourceFileElement withReturnTypeChange() {
				return ofFile(getSourceFile().withContent(__ -> EXPORT_DEFINES + externC("MYLIB_EXPORT long greet() { return 32; }")));
			}
		}

		class CppMainUsingApiHeader extends SourceFileElement {
			private final boolean useExternC;
			private final SymbolKind kind;

			public CppMainUsingApiHeader(boolean useExternC) {
				this(useExternC, SymbolKind.FUNCTION);
			}

			private CppMainUsingApiHeader(boolean useExternC, SymbolKind kind) {
				this.useExternC = useExternC;
				this.kind = kind;
			}

			private String externC(String s) {
				return useExternC ? "extern \"C\" " + s : s;
			}

			@Override
			public SourceFile getSourceFile() {
				return sourceFile("main.cpp", EXPORT_DEFINES + """
					%s
					int foo();
					int main() {
						return (%s == 32 ? 0 : 1) + foo();
					}
					""".formatted(symbolDeclaration(), symbolUsage()));
			}

			private String symbolDeclaration() {
				return switch (kind) {
					case FUNCTION -> useExternC ? "extern \"C\" MYLIB_EXPORT int greet();" : "MYLIB_EXPORT int greet();";
					case VARIABLE -> useExternC ? "extern \"C\" MYLIB_EXPORT int greet;" : "extern MYLIB_EXPORT int greet;";
				};
			}

			private String symbolUsage() {
				return switch (kind) {
					case FUNCTION -> "greet()";
					case VARIABLE -> "greet";
				};
			}

			public SourceFileElement useAsVariableSymbol() {
				return new CppMainUsingApiHeader(useExternC, SymbolKind.VARIABLE);
			}
		}
	}
}

// TODO: Test a library that change EI_DATA but same export table -> relink fails. Left out for now because
//  there is no honest way to produce it: flipping the byte order of an ELF file is not something a compiler
//  flag asks for, and a big-endian shared library needs a cross toolchain no host here has. Rewriting the
//  byte by hand would be testing a file no linker would ever hand us.
// TODO: overlinking (rpath-link) second-level library remove a symbol -> relink fails if strict mode (-z defs) but no relink if not strict mode
// TODO: Test __attribute__((alias("target"))) as an API change
// TODO: Test adding/removing const as an implementation change in C
