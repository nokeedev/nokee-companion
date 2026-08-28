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
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static dev.gradleplugins.buildscript.blocks.ApplyStatement.Notation.plugin;
import static dev.gradleplugins.buildscript.blocks.ApplyStatement.apply;
import static dev.gradleplugins.buildscript.syntax.Syntax.*;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static dev.nokee.companion.fixtures.PathExtensions.write;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static dev.nokee.elements.nativebase.NativeSourceElement.ofSources;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LinkAvoidanceFunctionalTests {
	GradleBuild build;
	GradleRunner runner;
	GradleRunnerArguments args = GradleRunnerArguments.create().withInfoLogging();

	@BeforeEach
	void setup(@TempDir Path testDirectory) throws IOException {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.properties(it -> {
			it.put("org.gradle.configuration-cache", true);
			it.put("dev.nokee.native-companion.link-avoidance.enabled", true);
		});
		build.rootProject(project -> {
			project.append(staticImportClass(OperatingSystem.class));
			project.append(staticImportClass(DefaultNativePlatform.class));
			project.append(importClass(NativeToolChainRegistry.class));
			project.append(apply(plugin(StandardToolChainsPlugin.class)));
			project.append(groovyDsl("""
				def toolChains = project.modelRegistry.realize('toolChains', NativeToolChainRegistry)

				void sharedLib(String name) {
					def cppTask = tasks.register("compile${name.capitalize()}", CppCompile) {
						objectFileDir = layout.buildDirectory.dir("obj/$name")
						source.from(fileTree("src/$name/cpp"))
						includes.from("src/$name/headers")
					}

					def linkTask = tasks.register("link${name.capitalize()}", LinkSharedLibrary) {
						source.from(cppTask.flatMap { it.objectFileDir }.map { it.asFileTree.matching { include('**/*.o', '**/*.obj') } })
						linkedFile = layout.buildDirectory.file(current().getSharedLibraryName("out/$name/$name"))
					}

					tasks.named { it == 'link' }.configureEach {
						libs.from(linkTask.flatMap { it.linkedFile })
					}
				}

				void staticLib(String name) {
					def cppTask = tasks.register("compile${name.capitalize()}", CppCompile) {
						objectFileDir = layout.buildDirectory.dir("obj/$name")
						source.from(fileTree("src/$name/cpp"))
						includes.from("src/$name/headers")
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
				return sourceFile("impl2.cpp", """
						int bye() { return 32; }
					""");
			}
		};
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
		@Test
		void doesNotRelinkOnImplementationOnlyChange() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withImplementationOnlyChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void relinkOnNewExportedSymbol() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			addedSymbol().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenStaticFunctionAdded() {
			// A static function has internal linkage, i.e. private to its compilation unit, so it never
			// reaches the exported symbol table and adding one must not change the ABI seen by consumers.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withAddedStaticFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenStaticVariableAdded() {
			// A static variable has internal linkage, i.e. private to its compilation unit, so it never
			// reaches the exported symbol table and adding one must not change the ABI seen by consumers.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withAddedStaticVariable().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenAnonymousNamespaceFunctionAdded() {
			// A function in an anonymous namespace also has internal linkage (a mangled, LOCAL symbol),
			// so - like a static function - it stays out of the exported symbol table and must not relink.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withAddedAnonymousNamespaceFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void relinkWhenInlineFunctionAdded() {
			// Sentinel for the opposite boundary: an inline function keeps external linkage and is emitted
			// as a weak (COMDAT) exported symbol, so it IS part of the ABI and adding one must relink.
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withAddedInlineFunction().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));
		}

		@Test
		void alwaysRelinkAfterClean() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			assertThat(runs(runner.withArguments(args.withTasks(":clean", ":link").toList())), tasksExecuted(hasItem(":link")));
		}

		@Test
		void relinkOnRemovedExportedSymbol() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withRenamedAbiChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));
		}

		@Test
		void relinkOnSymbolStrongnessTransition() {
			assumeFalse(SystemUtils.IS_OS_WINDOWS, "Weak symbols require GCC/Clang"); // TODO: assert toolchain capability not OS

			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withWeakSymbolChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));
		}

		private Path fooComponent() {
			return build.getLocation().resolve("src/foo/cpp");
		}

		private Path mainComponent() {
			return build.getLocation().resolve("src/main/cpp");
		}

		@Test
		void relinkOnSymbolTypeChangesFromFunctionToVariable() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withVariableKindChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));

			// TODO: Replace with ExportedSymbolEx().asVariable()
//			fixture.lib.api.withVariableKindChange().writeToDirectory(build.getLocation().resolve("includes"));
			fixture.app.main.useAsVariableSymbol().writeToDirectory(mainComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));

			// TODO: SEEMS TO BE ONLY UNDEFINED
//			fixture.lib.api.writeToDirectory(build.getLocation().resolve("includes")); // Return to original
			fixture.lib.impl.writeToDirectory(fooComponent()); // Return to original
			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));
		}

		@Test
		void relinkWhenParameterCountChanges() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.addParameterChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecutedAndNotSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenReturnTypeChanges() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withReturnTypeChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenFunctionBecomesVariableInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withVariableKindChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));

			// TODO: Replace with ExportedSymbolEx().asVariable()
//			fixture.lib.api.withVariableKindChange().writeToDirectory(build.getLocation().resolve("include"));
			fixture.app.main.useAsVariableSymbol().writeToDirectory(mainComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl))); // Return to original
			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenParameterCountChangesInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.addParameterChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenReturnTypeChangesInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			build.rootProject(sharedLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withReturnTypeChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksSkipped(hasItem(":link")));
		}

		@Test
		void doesNotRelinkWhenLibraryChangeLocationButNotAbi() {
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

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

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

		@Test
		void relinkWhenStaticLibraryImplementationChanges() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.rootProject(staticLibComponent("foo"));
			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			fixture.lib.impl.withImplementationOnlyChange().writeToDirectory(fooComponent());

			assertThat(runs(runner.withArguments(args.withTasks(":link").toList())), tasksExecuted(hasItem(":link")));
		}

		@Test
		void realizeTaskLibraryOnlyDuringExecutionPhase() {
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

			assertThat(theBuild(runner.withArguments(":link")), becomesUpToDate());

			ExecutedBuild result = runs(runner.withArguments(":link", "-Padditional-lib=" + sharedLib("other-lib/build/lib/main/debug/libother-lib")));
			assertThat(result, tasksExecuted(hasItem(":link")));
			assertThat(result.task(":link"), output(containsString("resolving additional-lib: other-lib/build/lib/main/debug/libother-lib")));
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

	private String sharedLib(String path) {
		Path sharedLib = build.getLocation().resolve(path);
		Path searchDir = sharedLib.getParent();
		String fileName = sharedLib.getFileName().toString();
		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(searchDir)) {
			for (Path dir : dirStream) {
				if (dir.getFileName().toString().startsWith(fileName)) {
					return build.getLocation().relativize(dir).toString();
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		throw new RuntimeException();
	}

	private static class Fixture {
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
				return ofSources(main);
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
				return sourceFile("impl.cpp", externC("int greet() { return 32; }"));
			}

			public SourceFileElement withImplementationOnlyChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet() { return 100; }")));
			}

			// The following changes add a symbol that is private to this compilation unit (internal
			// linkage). greet() references the added symbol so it genuinely lands in the object file
			// (the debug variant is unoptimized, so a referenced symbol is neither dead-stripped nor
			// inlined away), and greet() still returns 32 so the exported ABI is otherwise unchanged.
			// These are C++-only constructs, so they are never combined with extern "C".
			public SourceFileElement withAddedStaticFunction() {
				return ofFile(getSourceFile().withContent(__ -> "static int helper() { return 32; }\nint greet() { return helper(); }"));
			}

			public SourceFileElement withAddedStaticVariable() {
				// volatile keeps the read (and thus the storage) even if the variant were ever optimized.
				return ofFile(getSourceFile().withContent(__ -> "static volatile int counter = 32;\nint greet() { return counter; }"));
			}

			public SourceFileElement withAddedAnonymousNamespaceFunction() {
				return ofFile(getSourceFile().withContent(__ -> "namespace { int helper() { return 32; } }\nint greet() { return helper(); }"));
			}

			// Unlike the above, an inline function keeps external linkage and is emitted as a weak
			// exported symbol, so adding it is expected to relink.
			public SourceFileElement withAddedInlineFunction() {
				return ofFile(getSourceFile().withContent(__ -> "inline int helper() { return 32; }\nint greet() { return helper(); }"));
			}

			public SourceFileElement withRenamedAbiChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet_renamed() { return 32; }")));
			}

			public SourceFileElement withWeakSymbolChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("__attribute__((weak)) int greet() { return 32; }")));
			}

			private String externC(String s) {
				return useExternC ? "extern \"C\" " + s : s;
			}

			public SourceFileElement withVariableKindChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet = 32;")));
			}

			public SourceFileElement addParameterChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("int greet(int foo) { return foo + 32; }")));
			}

			public SourceFileElement withReturnTypeChange() {
				return ofFile(getSourceFile().withContent(__ -> externC("long greet() { return 32; }")));
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
				return sourceFile("main.cpp", """
					%s
					int main() {
						return %s == 32 ? 0 : 1;
					}
					""".formatted(symbolDeclaration(), symbolUsage()));
			}

			private String symbolDeclaration() {
				return switch (kind) {
					case FUNCTION -> useExternC ? "extern \"C\" int greet();" : "int greet();";
					case VARIABLE -> useExternC ? "extern \"C\" int greet;" : "extern int greet;";
				};
			}

			private String symbolUsage() {
				return switch (kind) {
					case FUNCTION -> "greet()";
					case VARIABLE -> "greet";
				};
			}

			public SourceFileElement useAsVariableSymbol() {
				return new CppMainUsingApiHeader(SymbolKind.VARIABLE);
			}
		}
	}
}
