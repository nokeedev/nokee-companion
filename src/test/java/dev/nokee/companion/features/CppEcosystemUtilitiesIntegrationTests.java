package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.SkipWhenNoSubject;
import dev.nokee.commons.fixtures.Subject;
import dev.nokee.commons.fixtures.SubjectExtension;
import dev.nokee.companion.CppEcosystemUtilities;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.*;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithInstallation;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;
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
import static org.hamcrest.MatcherAssert.assertThat;

@ExtendWith(SubjectExtension.class)
class CppEcosystemUtilitiesIntegrationTests {
	@Subject Project project;
	@TempDir Path testDirectory;
	@Subject CppEcosystemUtilities subject;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		subject = CppEcosystemUtilities.forProject(project);
	}

	interface Tester {
		@Test
		default void canAccessCppCompileConfigurationOnBinaries(@Subject CppBinary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.cppCompileOf(binary), doesNotThrowException());
			assertThat(access.cppCompileOf(binary), named(cppCompileConfigurationName(binary)));
			assertThat(access.cppCompileOf(binary), providerOf(named(cppCompileConfigurationName(binary))));
		}

		@Test
		default void canAccessNativeLinkConfigurationOnBinaries(@Subject CppBinary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.nativeLinkOf(binary), doesNotThrowException());
			assertThat(access.nativeLinkOf(binary), named(nativeLinkConfigurationName(binary)));
			assertThat(access.nativeLinkOf(binary), providerOf(named(nativeLinkConfigurationName(binary))));
		}

		@Test
		default void canAccessNativeRuntimeConfigurationOnBinaries(@Subject CppBinary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.nativeRuntimeOf(binary), doesNotThrowException());
			assertThat(access.nativeRuntimeOf(binary), named(nativeRuntimeConfigurationName(binary)));
			assertThat(access.nativeRuntimeOf(binary), providerOf(named(nativeRuntimeConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkElementsConfigurationOnStaticLibraries(@Subject CppStaticLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.linkElementsOf(binary), doesNotThrowException());
			assertThat(access.linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
			assertThat(access.linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnStaticLibraries(@Subject CppStaticLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.runtimeElementsOf(binary), doesNotThrowException());
			assertThat(access.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(access.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkElementsConfigurationOnSharedLibraries(@Subject CppSharedLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.linkElementsOf(binary), doesNotThrowException());
			assertThat(access.linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
			assertThat(access.linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnSharedLibraries(@Subject CppSharedLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.runtimeElementsOf(binary), doesNotThrowException());
			assertThat(access.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(access.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRuntimeElementsConfigurationOnExecutables(@Subject CppExecutable binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.runtimeElementsOf(binary), doesNotThrowException());
			assertThat(access.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
			assertThat(access.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
		}

		@Test
		default void canAccessCompileTaskOnBinaries(@Subject CppBinary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.compileTaskOf(binary), doesNotThrowException());
			assertThat(access.compileTaskOf(binary), named(compileTaskName(binary)));
			assertThat(access.compileTaskOf(binary), providerOf(named(compileTaskName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkTaskOnExecutables(@Subject ComponentWithExecutable binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.linkTaskOf(binary), doesNotThrowException());
			assertThat(access.linkTaskOf(binary), named(linkTaskName((CppBinary) binary)));
			assertThat(access.linkTaskOf(binary), providerOf(named(linkTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkTaskOnSharedLibraries(@Subject ComponentWithSharedLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.linkTaskOf(binary), doesNotThrowException());
			assertThat(access.linkTaskOf(binary), named(linkTaskName((CppBinary) binary)));
			assertThat(access.linkTaskOf(binary), providerOf(named(linkTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessInstallTaskOnExecutables(@Subject ComponentWithInstallation binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.installTaskOf(binary), doesNotThrowException());
			assertThat(access.installTaskOf(binary), named(installTaskName((CppBinary) binary)));
			assertThat(access.installTaskOf(binary), providerOf(named(installTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessCreateTaskOnStaticLibraries(@Subject ComponentWithStaticLibrary binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.createTaskOf(binary), doesNotThrowException());
			assertThat(access.createTaskOf(binary), named(createTaskName((CppBinary) binary)));
			assertThat(access.createTaskOf(binary), providerOf(named(createTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRunTaskOnTestExecutables(@Subject CppTestExecutable binary, @Subject CppEcosystemUtilities access) {
			assertThat(() -> access.runTaskOf(binary), doesNotThrowException());
			assertThat(access.runTaskOf(binary), named(runTaskName(binary)));
			assertThat(access.runTaskOf(binary), providerOf(named(runTaskName(binary))));
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
