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

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.doesNotThrowException;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryConfigurationExtensions.*;
import static org.hamcrest.MatcherAssert.assertThat;

@ExtendWith(SubjectExtension.class)
class CppBinaryConfigurationExtensionsIntegrationTests {
	@Subject Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	interface Tester {
		@Test
		default void canAccessCppCompileConfigurationOnBinaries(@Subject CppBinary binary) {
			assertThat(() -> cppCompileOf(binary), doesNotThrowException());
			assertThat(cppCompileOf(binary), named(cppCompileConfigurationName(binary)));
			assertThat(cppCompileOf(binary), providerOf(named(cppCompileConfigurationName(binary))));
		}

		@Test
		default void canAccessNativeLinkConfigurationOnBinaries(@Subject CppBinary binary) {
			assertThat(() -> nativeLinkOf(binary), doesNotThrowException());
			assertThat(nativeLinkOf(binary), named(nativeLinkConfigurationName(binary)));
			assertThat(nativeLinkOf(binary), providerOf(named(nativeLinkConfigurationName(binary))));
		}

		@Test
		default void canAccessNativeRuntimeConfigurationOnBinaries(@Subject CppBinary binary) {
			assertThat(() -> nativeRuntimeOf(binary), doesNotThrowException());
			assertThat(nativeRuntimeOf(binary), named(nativeRuntimeConfigurationName(binary)));
			assertThat(nativeRuntimeOf(binary), providerOf(named(nativeRuntimeConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkElementsConfigurationOnStaticLibraries(@Subject CppStaticLibrary binary) {
			assertThat(() -> linkElementsOf(binary), doesNotThrowException());
			assertThat(linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
			assertThat(linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnStaticLibraries(@Subject CppStaticLibrary binary) {
			assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
			assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkElementsConfigurationOnSharedLibraries(@Subject CppSharedLibrary binary) {
			assertThat(() -> linkElementsOf(binary), doesNotThrowException());
			assertThat(linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
			assertThat(linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnSharedLibraries(@Subject CppSharedLibrary binary) {
			assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
			assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnExecutables(@Subject CppExecutable binary) {
			assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
			assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
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
		class StaticLibraries implements Tester {
			@Subject CppStaticLibrary debugBinary() {
				((ProjectInternal) project).evaluate();
				return project.getComponents().withType(CppStaticLibrary.class).getByName("mainDebugStatic");
			}
		}

		@Nested
		class SharedLibraries implements Tester {
			@Subject CppSharedLibrary debugBinary() {
				((ProjectInternal) project).evaluate();
				return project.getComponents().withType(CppSharedLibrary.class).getByName("mainDebugShared");
			}
		}
	}

	@Nested
	class WhenCppApplicationPluginApplied implements Tester {
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
	class WhenCppUnitTestPluginApplied implements Tester {
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
