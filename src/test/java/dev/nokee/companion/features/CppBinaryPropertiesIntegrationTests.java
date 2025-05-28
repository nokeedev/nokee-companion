package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.SkipWhenNoSubject;
import dev.nokee.commons.fixtures.Subject;
import dev.nokee.commons.fixtures.SubjectExtension;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.*;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.companion.CppBinaryProperties.debuggabilityOf;
import static dev.nokee.companion.CppBinaryProperties.optimizationOf;
import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(SubjectExtension.class)
class CppBinaryPropertiesIntegrationTests {
	@Subject Project project;
	@TempDir Path testDirectory;
	@Subject CppBinaryAccessors access;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		access = project.getObjects().newInstance(CppBinaryAccessors.class);
	}

	interface OptimizationTester {
		@Test
		default void honorsOptimizedShadowPropertyOnCppBinaries(@Subject CppBinary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isOptimized();
			optimizationOf(binary).mut(it -> !it);

			assertThat(access.cppCompileOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.nativeLinkOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.nativeRuntimeOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.compileTaskOf(binary).get().isOptimized(), is(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsOptimizedShadowPropertyOnOutgoingElementsOfStaticLibrary(@Subject CppStaticLibrary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isOptimized();
			optimizationOf(binary).mut(it -> !it);

			assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsOptimizedShadowPropertyOnOutgoingElementsOfSharedLibrary(@Subject CppSharedLibrary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isOptimized();
			optimizationOf(binary).mut(it -> !it);

			assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsOptimizedShadowPropertyOnOutgoingElementsOfExecutable(@Subject CppExecutable binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isOptimized();
			optimizationOf(binary).mut(it -> !it);

			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
		}
	}

	interface DebuggabilityTester {
		@Test
		default void honorsDebuggableShadowPropertyOnCppBinaries(@Subject CppBinary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isDebuggable();
			debuggabilityOf(binary).mut(it -> !it);

			assertThat(access.cppCompileOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.nativeLinkOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.nativeRuntimeOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.compileTaskOf(binary).get().isDebuggable(), is(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsDebuggableShadowPropertyOnOutgoingElementsOfStaticLibrary(@Subject CppStaticLibrary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isDebuggable();
			debuggabilityOf(binary).mut(it -> !it);

			assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsDebuggableShadowPropertyOnOutgoingElementsOfSharedLibrary(@Subject CppSharedLibrary binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isDebuggable();
			debuggabilityOf(binary).mut(it -> !it);

			assertThat(access.linkElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(DEBUGGABLE_ATTRIBUTE), is(expectedValue));
			assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsDebuggableShadowPropertyOnOutgoingElementsOfExecutable(@Subject CppExecutable binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isDebuggable();
			debuggabilityOf(binary).mut(it -> !it);

			assertThat(access.runtimeElementsOf(binary).get().getAttributes().getAttribute(OPTIMIZED_ATTRIBUTE), is(expectedValue));
			assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
		}

		@Test
		@SkipWhenNoSubject
		default void honorsDebuggableShadowPropertyOnOutgoingElementsOfTestExecutable(@Subject CppTestExecutable binary, @Subject CppBinaryAccessors access) {
			boolean expectedValue = !binary.isDebuggable();
			debuggabilityOf(binary).mut(it -> !it);

			assertThat(access.linkTaskOf(binary).get().getDebuggable(), providerOf(expectedValue));
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-library");
			project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.STATIC, Linkage.SHARED));
		}

		@Nested
		class StaticLibraries implements OptimizationTester, DebuggabilityTester {
			@Subject CppStaticLibrary debugBinary() {
				((ProjectInternal) project).evaluate();
				return project.getComponents().withType(CppStaticLibrary.class).getByName("mainDebugStatic");
			}
		}

		@Nested
		class SharedLibraries implements OptimizationTester, DebuggabilityTester {
			@Subject CppSharedLibrary debugBinary() {
				((ProjectInternal) project).evaluate();
				return project.getComponents().withType(CppSharedLibrary.class).getByName("mainDebugShared");
			}
		}
	}

	@Nested
	class WhenCppApplicationPluginApplied implements OptimizationTester, DebuggabilityTester {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-application");
		}

		@Subject CppExecutable debugBinary() {
			((ProjectInternal) project).evaluate();
			return project.getComponents().withType(CppExecutable.class).getByName("mainDebug");
		}
	}

	@Nested
	class WhenCppUnitTestPluginApplied implements OptimizationTester, DebuggabilityTester {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-unit-test");
		}

		@Subject CppTestExecutable debugBinary() {
			((ProjectInternal) project).evaluate();
			return project.getComponents().withType(CppTestExecutable.class).getByName("testExecutable");
		}
	}
}
