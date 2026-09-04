package dev.nokee.companion.features;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.*;
import org.gradle.nativeplatform.Linkage;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.doesNotThrowException;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.commons.names.CppNames.*;
import static dev.nokee.companion.CppBinaryConfigurationExtensions.*;
import static org.hamcrest.MatcherAssert.assertThat;

class CppBinaryConfigurationExtensionsIntegrationTests {
	Project project;

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	abstract class Tester {
		@Test
		void canAccessCppCompileConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> cppCompileOf(binary), doesNotThrowException());
				assertThat(cppCompileOf(binary), named(cppCompileConfigurationName(binary)));
				assertThat(cppCompileOf(binary), providerOf(named(cppCompileConfigurationName(binary))));
			}
		}

		@Test
		void canAccessNativeLinkConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> nativeLinkOf(binary), doesNotThrowException());
				assertThat(nativeLinkOf(binary), named(nativeLinkConfigurationName(binary)));
				assertThat(nativeLinkOf(binary), providerOf(named(nativeLinkConfigurationName(binary))));
			}
		}

		@Test
		void canAccessNativeRuntimeConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> nativeRuntimeOf(binary), doesNotThrowException());
				assertThat(nativeRuntimeOf(binary), named(nativeRuntimeConfigurationName(binary)));
				assertThat(nativeRuntimeOf(binary), providerOf(named(nativeRuntimeConfigurationName(binary))));
			}
		}

		@Test
		void canAccessLinkElementsConfigurationOnStaticLibraries() {
			for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
				assertThat(() -> linkElementsOf(binary), doesNotThrowException());
				assertThat(linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
				assertThat(linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnStaticLibraries() {
			for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
				assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
				assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessLinkElementsConfigurationOnSharedLibraries() {
			for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
				assertThat(() -> linkElementsOf(binary), doesNotThrowException());
				assertThat(linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
				assertThat(linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnSharedLibraries() {
			for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
				assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
				assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnExecutables() {
			for (CppExecutable binary : project.getComponents().withType(CppExecutable.class)) {
				assertThat(() -> runtimeElementsOf(binary), doesNotThrowException());
				assertThat(runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
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
