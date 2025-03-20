package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.Subject;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CppCompileIntegrationTests {
	Project project;
	@TempDir Path testDirectory;
	CppCompile compileTask;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		project.getPlugins().apply(NativeComponentPlugin.class);
		((ProjectInternal) project).getModelRegistry().realize("toolChains", NativeToolChainRegistry.class);

		compileTask = project.getTasks().create("compile", CppCompileTask.class);
		compileTask.getTargetPlatform().set(DefaultNativePlatform.host());
		compileTask.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("gcc"));
	}

	@Test
	void canCreateCppCompileTask() {
		assertThat(project.getTasks().create("test", CppCompile.clazz()), instanceOf(CppCompileTask.class));
	}

	@Test
	void defaultsToNonDebuggable() {
		assertThat(compileTask.getOptions().getDebuggable(), providerOf(false));
	}

	@Test
	void defaultsToUnoptimized() {
		assertThat(compileTask.getOptions().getOptimized(), providerOf(false));
	}

	@Test
	void defaultsToPositionDependentCode() {
		assertThat(compileTask.getOptions().getPositionIndependentCode(), providerOf(false));
	}

	@Test
	void defaultsToEmptyCompilerArgs() {
		assertThat(compileTask.getCompilerArgs(), providerOf(emptyIterable()));
		assertThat(compileTask.getOptions().getCompilerArgs(), providerOf(emptyIterable()));
	}

	@Nested
	class DebuggablePropertyTests {
		@Test
		void linkLegacyDebuggablePropertyToOptions() {
			compileTask.getOptions().getDebuggable().set(true);
			assertThat(compileTask.isDebuggable(), is(true));

			compileTask.setDebuggable(false);
			assertThat(compileTask.getOptions().getDebuggable(), providerOf(false));
		}
	}

	@Nested
	class OptimizedPropertyTests {
		@Test
		void linkLegacyOptimizedPropertyToOptions() {
			compileTask.getOptions().getOptimized().set(true);
			assertThat(compileTask.isOptimized(), is(true));

			compileTask.setOptimized(false);
			assertThat(compileTask.getOptions().getOptimized(), providerOf(false));
		}
	}

	@Nested
	class PositionIndependentCodePropertyTests {
		@Test
		void linkLegacyPositionIndependentCodePropertyToOptions() {
			compileTask.getOptions().getPositionIndependentCode().set(true);
			assertThat(compileTask.isPositionIndependentCode(), is(true));

			compileTask.setPositionIndependentCode(false);
			assertThat(compileTask.getOptions().getPositionIndependentCode(), providerOf(false));
		}
	}

	@Nested
	class CompilerArgsPropertyTests {
		@Test
		void linkLegacyCompilerArgsPropertyToOptions() {
			compileTask.getOptions().getCompilerArgs().set(List.of("-DFOO"));
			assertThat(compileTask.getCompilerArgs(), providerOf(contains("-DFOO")));

			compileTask.getCompilerArgs().set(List.of("-DBAR"));
			assertThat(compileTask.getOptions().getCompilerArgs(), providerOf(contains("-DBAR")));
		}

		@Test
		void defaultsToNoCompilerArgsProviders() {
			assertThat(compileTask.getOptions().getCompilerArgumentProviders(), providerOf(emptyIterable()));
		}
	}

	@Nested
	class MacrosTests {
		@Nested
		class LegacyMacrosMapTests extends MacroTester {
			@Subject Macros subject() {
				return newMacros(compileTask);
			}

			@Subject("with-macros") Macros subjectWithEntries() {
				return newMacros(compileTask).define("MACRO1").define("MACRO2", "val2");
			}

			@Test
			void reflectsNewEntriesInLegacyMacrosMapInPreprocessorOptionsDefines() {
				compileTask.getMacros().put("MACRO1", null);
				compileTask.getMacros().put("MACRO2", "val2");

				// TODO: Assert definition
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(contains(named("MACRO1"), named("MACRO2"))));
			}

			@Test
			void reflectsClear() {
				compileTask.getMacros().put("MACRO1", null);
				compileTask.getMacros().put("MACRO2", "val2");
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(contains(named("MACRO1"), named("MACRO2"))));

				compileTask.getOptions().getPreprocessorOptions().getDefinedMacros().empty();
				assertThat(compileTask.getMacros(), anEmptyMap());
			}
		}

		@Nested
		class PreprocessorOptionsDefinedMacrosTests extends MacroTester {
			@Subject Macros subject() {
				return newMacros(compileTask.getOptions().getPreprocessorOptions());
			}

			@Subject("with-macros") Macros subjectWithEntries() {
				return newMacros(compileTask.getOptions().getPreprocessorOptions()).define("MACRO1").define("MACRO2", "val2");
			}

			@Test
			void reflectsPreprocessorOptionsDefinesWithoutDefinitionInLegacyMacrosMap() {
				compileTask.getOptions().getPreprocessorOptions().define("MACRO1");
				compileTask.getOptions().getPreprocessorOptions().defines(Collections.singletonMap("MACRO2", null));
				compileTask.getOptions().getPreprocessorOptions().defines(project.provider(() -> Collections.singletonMap("MACRO3", null)));

				assertThat(compileTask.getMacros(), aMapWithSize(3));
				assertThat(compileTask.getMacros().keySet(), contains("MACRO1", "MACRO2", "MACRO3"));
				assertThat(compileTask.getMacros().values(), contains(null, null, null));
				assertThat(compileTask.getMacros().get("MACRO2"), nullValue());
				assertThat(compileTask.getMacros().getOrDefault("MACRO3", "<null>"), nullValue());
				assertThat(compileTask.getMacros().getOrDefault("NON_EXISTENT_MACRO", "<null>"), equalTo("<null>"));
			}

			@Test
			void reflectsPreprocessorOptionsDefinesInLegacyMacrosMap() {
				compileTask.getOptions().getPreprocessorOptions().define("MACRO1", "val1");
				compileTask.getOptions().getPreprocessorOptions().defines(Collections.singletonMap("MACRO2", "val2"));
				compileTask.getOptions().getPreprocessorOptions().defines(project.provider(() -> Collections.singletonMap("MACRO3", "val3")));

				assertThat(compileTask.getMacros(), aMapWithSize(3));
				assertThat(compileTask.getMacros().keySet(), contains("MACRO1", "MACRO2", "MACRO3"));
				assertThat(compileTask.getMacros().values(), contains("val1", "val2", "val3"));
				assertThat(compileTask.getMacros().get("MACRO2"), equalTo("val2"));
				assertThat(compileTask.getMacros().getOrDefault("MACRO3", "<null>"), equalTo("val3"));
				assertThat(compileTask.getMacros().getOrDefault("NON_EXISTENT_MACRO", "<null>"), equalTo("<null>"));
			}

			@Test
			void reflectsRemovedEntriesInLegacyMacrosMapInPreprocessorOptionsDefines() {
				compileTask.getMacros().put("MACRO1", null);
				compileTask.getMacros().put("MACRO2", "val2");
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(contains(named("MACRO1"), named("MACRO2"))));

				compileTask.getMacros().remove("MACRO1");
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(contains(named("MACRO2"))));

				compileTask.getMacros().remove("MACRO2");
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(empty()));
			}

			@Test
			void reflectsClear() {
				compileTask.getMacros().put("MACRO1", null);
				compileTask.getMacros().put("MACRO2", "val2");
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(contains(named("MACRO1"), named("MACRO2"))));

				compileTask.getMacros().clear();
				assertThat(compileTask.getOptions().getPreprocessorOptions().getDefinedMacros(), providerOf(empty()));
			}
		}
	}
}
