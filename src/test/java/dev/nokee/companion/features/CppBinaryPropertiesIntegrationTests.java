package dev.nokee.companion.features;

import dev.nokee.companion.CppEcosystemUtilities;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.*;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class CppBinaryPropertiesIntegrationTests {
	Project project;
	CppEcosystemUtilities access;

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		access = CppEcosystemUtilities.forProject(project);
	}

	abstract class Tester {
		@Nested
		class OptimizationProperties {
			@Test
			void honorsOptimizedShadowPropertyOnCppBinaries() {
				for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
					boolean expectedValue = !binary.isOptimized();
					access.optimizationOf(binary).mut(it -> !it);

					assertThat(access.cppCompileOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.nativeLinkOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.nativeRuntimeOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.compileTaskOf(binary).get().isOptimized(), is(expectedValue));
				}
			}

			@Test
			void honorsOptimizedShadowPropertyOnOutgoingElementsOfStaticLibrary() {
				for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
					boolean expectedValue = !binary.isOptimized();
					access.optimizationOf(binary).mut(it -> !it);

					assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
				}
			}

			@Test
			void honorsOptimizedShadowPropertyOnOutgoingElementsOfSharedLibrary() {
				for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
					boolean expectedValue = !binary.isOptimized();
					access.optimizationOf(binary).mut(it -> !it);

					assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
				}
			}

			@Test
			void honorsOptimizedShadowPropertyOnOutgoingElementsOfExecutable() {
				for (CppExecutable binary : project.getComponents().withType(CppExecutable.class)) {
					boolean expectedValue = !binary.isOptimized();
					access.optimizationOf(binary).mut(it -> !it);

					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
				}
			}
		}

		@Nested
		class DebuggabilityProperties {
			@Test
			void honorsDebuggableShadowPropertyOnCppBinaries() {
				for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
					boolean expectedValue = !binary.isDebuggable();
					access.debuggabilityOf(binary).mut(it -> !it);

					assertThat(access.cppCompileOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.nativeLinkOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.nativeRuntimeOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.compileTaskOf(binary).get().isDebuggable(), is(expectedValue));
				}
			}

			@Test
			void honorsDebuggableShadowPropertyOnOutgoingElementsOfStaticLibrary() {
				for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
					boolean expectedValue = !binary.isDebuggable();
					access.debuggabilityOf(binary).mut(it -> !it);

					assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
				}
			}

			@Test
			void honorsDebuggableShadowPropertyOnOutgoingElementsOfSharedLibrary() {
				for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
					boolean expectedValue = !binary.isDebuggable();
					access.debuggabilityOf(binary).mut(it -> !it);

					assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
					assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
				}
			}

			@Test
			void honorsDebuggableShadowPropertyOnOutgoingElementsOfExecutable() {
				for (CppExecutable binary : project.getComponents().withType(CppExecutable.class)) {
					boolean expectedValue = !binary.isDebuggable();
					access.debuggabilityOf(binary).mut(it -> !it);

					assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
					assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
				}
			}

			@Test
			void honorsDebuggableShadowPropertyOnOutgoingElementsOfTestExecutable() {
				for (CppTestExecutable binary : project.getComponents().withType(CppTestExecutable.class)) {
					boolean expectedValue = !binary.isDebuggable();
					access.debuggabilityOf(binary).mut(it -> !it);

					assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
				}
			}
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied extends Tester {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-library");
			project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.STATIC, Linkage.SHARED));
			((ProjectInternal) project).evaluate();
		}
	}

	@Nested
	class WhenCppApplicationPluginApplied extends Tester {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-application");
			((ProjectInternal) project).evaluate();
		}
	}

	@Nested
	class WhenCppUnitTestPluginApplied extends Tester {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-unit-test");
			((ProjectInternal) project).evaluate();
		}
	}
}
