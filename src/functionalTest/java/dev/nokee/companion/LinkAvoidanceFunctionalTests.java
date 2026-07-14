package dev.nokee.companion;

import dev.nokee.companion.fixtures.GradleBuild;
import dev.nokee.elements.core.*;
import dev.nokee.elements.nativebase.NativeElement;
import dev.nokee.elements.nativebase.NativeLibraryElement;
import org.apache.commons.lang3.SystemUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static dev.nokee.companion.fixtures.GradleTestKitMatchers.*;
import static dev.nokee.companion.fixtures.PathExtensions.write;
import static dev.nokee.elements.core.ProjectElement.ofMain;
import static dev.nokee.elements.nativebase.NativeLibraryElement.ofPublicHeaders;
import static dev.nokee.elements.nativebase.NativeSourceElement.ofSources;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LinkAvoidanceFunctionalTests {
	@TempDir Path testDirectory;
	GradleBuild build;
	GradleRunner runner;

	@BeforeEach
	void setup() throws IOException {
		build = GradleBuild.inDirectory(testDirectory);
		runner = GradleRunner.create().withProjectDir(build.getLocation().toFile()).withPluginClasspath().forwardOutput();

		build.properties(it -> {
			it.put("org.gradle.configuration-cache", true);
		});
		build.subproject("lib", project -> {
			project.plugins(it -> {
				it.id("cpp-library");
			});
		});

		build.subproject("app", project -> {
			project.plugins(it -> {
				it.id("dev.nokee.native-companion");
			});
			project.append(groovyDsl("""
				dependencies {
					implementation project(':lib')
				}
			"""));
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
			build.subproject("app", project -> project.plugins(it -> it.id("cpp-application")));
		}
	}

	@Nested
	class LinkSharedLibraryTests extends LinkAvoidanceTester {
		@BeforeEach
		void setup() {
			build.subproject("app", project -> project.plugins(it -> it.id("cpp-library")));
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
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withImplementationOnlyChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkOnNewExportedSymbol() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(addedSymbol())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));
		}

		@Test
		void alwaysRelinkAfterClean() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			assertThat(runner.withArguments("clean", ":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkOnRemovedExportedSymbol() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withRenamedAbiChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecutedAndNotSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkOnSymbolStrongnessTransition() {
			assumeFalse(SystemUtils.IS_OS_WINDOWS, "Weak symbols require GCC/Clang"); // TODO: assert toolchain capability not OS

			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withWeakSymbolChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkOnSymbolTypeChangesFromFunctionToVariable() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withVariableKindChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecutedAndNotSkipped(hasItem(":app:linkDebug")));

			// TODO: Replace with ExportedSymbolEx().asVariable()
			build.subproject("lib", writeToProject(ofPublicHeaders(fixture.lib.api.withVariableKindChange())));
			build.subproject("app", writeToProject(ofSources(fixture.app.main.useAsVariableSymbol())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));

			// TODO: SEEMS TO BE ONLY UNDEFINED
			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl))); // Return to original
			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkWhenParameterCountChanges() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.addParameterChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecutedAndNotSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void doesNotRelinkWhenReturnTypeChanges() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withReturnTypeChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void doesNotRelinkWhenFunctionBecomesVariableInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withVariableKindChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));

			// TODO: Replace with ExportedSymbolEx().asVariable()
			build.subproject("lib", writeToProject(ofPublicHeaders(fixture.lib.api.withVariableKindChange())));
			build.subproject("app", writeToProject(ofSources(fixture.app.main.useAsVariableSymbol())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl))); // Return to original
			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void doesNotRelinkWhenParameterCountChangesInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.addParameterChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void doesNotRelinkWhenReturnTypeChangesInC() {
			var fixture = new Fixture().usingExternC();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withReturnTypeChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void doesNotRelinkWhenLibraryChangeLocationButNotAbi() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.subproject("lib", project -> {
				project.append(groovyDsl("""
					afterEvaluate {
						tasks.withType(LinkSharedLibrary).configureEach {
							installName = linkedFile.get().asFile.name // use non-absolute default value
						}
					}
				"""));
			});

			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			// relocating a library should not cause a relink
			build.subproject("lib", project -> {
				project.append(groovyDsl("""
					afterEvaluate {
						tasks.withType(LinkSharedLibrary).configureEach {
							linkedFile = layout.buildDirectory.file(linkedFile.get().asFile.name) // safe-ish as we are just building one variant
						}
					}
				"""));
			});

			assertThat(runner.withArguments(":app:assemble").run(), tasksSkipped(hasItem(":app:linkDebug")));
		}

		@Test
		void relinkWhenStaticLibraryImplementationChanges() {
			build.subproject("lib", project -> {
				project.append(groovyDsl("""
					library {
						linkage = [Linkage.STATIC]
					}
				"""));
			});

			var fixture = new Fixture();
			fixture.writeToProject(build);
			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			build.subproject("lib", writeToProject(ofSources(fixture.lib.impl.withImplementationOnlyChange())));

			assertThat(runner.withArguments(":app:assemble").run(), tasksExecuted(hasItem(":app:linkDebug")));
		}

		@Test
		void realizeTaskLibraryOnlyDuringExecutionPhase() {
			var fixture = new Fixture();
			fixture.writeToProject(build);
			build.subproject("other-lib", project -> {
				write(project.file("src/main/cpp/foo.cpp"), "int foo_bar() { return 42; }");
				project.plugins(it -> it.id("cpp-library"));
			});
			runner.withArguments(":other-lib:assemble").build();

			build.subproject("app", project -> {
				project.append(groovyDsl("""
					components.withType(CppBinary).configureEach {
						linkTask.get().libs.from(providers.gradleProperty('additional-lib').orElse([]).map {
							println('resolving additional-lib: ' + it)
							return it
						})
					}
				"""));
			});

			assertThat(runner.withArguments(":app:assemble"), becomesUpToDate());

			BuildResult result = runner.withArguments(":app:assemble", "-Padditional-lib=" + sharedLib("other-lib/build/lib/main/debug/libother-lib")).run();
			assertThat(result, tasksExecuted(hasItem(":app:linkDebug")));
			assertThat(dev.gradleplugins.runnerkit.BuildResult.from(result.getOutput()).task(":app:linkDebug").getOutput(), containsString("resolving additional-lib: other-lib/build/lib/main/debug/libother-lib"));
		}
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

	private static class Fixture extends WorkspaceElement {
		private enum SymbolKind { FUNCTION, VARIABLE }
		private final CppApp app;
		private final CppLib lib;

		public Fixture() {
			this(false);
		}

		public Fixture(boolean useExternC) {
			this.app = new CppApp();
			this.lib = new CppLib(useExternC);
		}

		public Fixture usingExternC() {
			return new Fixture(true);
		}

		public class CppApp extends ProjectElement {
			public final CppMainUsingApiHeader main = new CppMainUsingApiHeader();

			@Override
			public Element getMainElement() {
				return ofSources(main);
			}
		}

		public class CppLib extends ProjectElement {
			public final CppApiHeader api;
			public final CppImpl impl;

			public CppLib(boolean useExternC) {
				this.api = new CppApiHeader(useExternC);
				this.impl = new CppImpl(useExternC);
			}

			@Override
			public NativeLibraryElement getMainElement() {
				return new NativeLibraryElement() {
					@Override
					public SourceElement getPublicHeaders() {
						return api;
					}

					@Override
					public SourceElement getSources() {
						return impl;
					}
				};
			}
		}

		@Override
		public List<ProjectElement> getProjects() {
			return List.of(app, lib);
		}

		public void writeToProject(GradleBuild build) {
			build.subproject("app", project -> {
				new GradleLayoutElement().applyTo(app).writeToDirectory(project.getLocation());
			});
			build.subproject("lib", project -> {
				new GradleLayoutElement().applyTo(lib).writeToDirectory(project.getLocation());
			});
		}

		class CppApiHeader extends SourceFileElement {
			private final boolean useExternC;

			public CppApiHeader(boolean useExternC) {
				this.useExternC = useExternC;
			}

			@Override
			public SourceFile getSourceFile() {
				return sourceFile("api.h", externC("int greet();"));
			}

			private String externC(String s) {
				return useExternC ? "extern \"C\" " + s : s;
			}

			public SourceFileElement withVariableKindChange() {
				return ofFile(sourceFile("api.h", externC("int greet;")));
			}
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
			private final SymbolKind kind;

			public CppMainUsingApiHeader() {
				this(SymbolKind.FUNCTION);
			}

			private CppMainUsingApiHeader(SymbolKind kind) {
				this.kind = kind;
			}

			@Override
			public SourceFile getSourceFile() {
				return sourceFile("main.cpp", """
					#include "api.h"
					int main() {
						return %s == 32 ? 0 : 1;
					}
					""".formatted(symbolUsage()));
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
