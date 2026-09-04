package dev.nokee.companion.features;

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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.doesNotThrowException;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.commons.names.CppNames.*;
import static org.hamcrest.MatcherAssert.assertThat;

class CppEcosystemUtilitiesIntegrationTests {
	Project project;
	CppEcosystemUtilities subject;

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		subject = CppEcosystemUtilities.forProject(project);
	}

	abstract class Tester {
		@Test
		void canAccessCppCompileConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> subject.cppCompileOf(binary), doesNotThrowException());
				assertThat(subject.cppCompileOf(binary), named(cppCompileConfigurationName(binary)));
				assertThat(subject.cppCompileOf(binary), providerOf(named(cppCompileConfigurationName(binary))));
			}
		}

		@Test
		void canAccessNativeLinkConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> subject.nativeLinkOf(binary), doesNotThrowException());
				assertThat(subject.nativeLinkOf(binary), named(nativeLinkConfigurationName(binary)));
				assertThat(subject.nativeLinkOf(binary), providerOf(named(nativeLinkConfigurationName(binary))));
			}
		}

		@Test
		void canAccessNativeRuntimeConfigurationOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> subject.nativeRuntimeOf(binary), doesNotThrowException());
				assertThat(subject.nativeRuntimeOf(binary), named(nativeRuntimeConfigurationName(binary)));
				assertThat(subject.nativeRuntimeOf(binary), providerOf(named(nativeRuntimeConfigurationName(binary))));
			}
		}

		@Test
		void canAccessLinkElementsConfigurationOnStaticLibraries() {
			for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
				assertThat(() -> subject.linkElementsOf(binary), doesNotThrowException());
				assertThat(subject.linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
				assertThat(subject.linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnStaticLibraries() {
			for (CppStaticLibrary binary : project.getComponents().withType(CppStaticLibrary.class)) {
				assertThat(() -> subject.runtimeElementsOf(binary), doesNotThrowException());
				assertThat(subject.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(subject.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessLinkElementsConfigurationOnSharedLibraries() {
			for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
				assertThat(() -> subject.linkElementsOf(binary), doesNotThrowException());
				assertThat(subject.linkElementsOf(binary), named(linkElementsConfigurationName(binary)));
				assertThat(subject.linkElementsOf(binary), providerOf(named(linkElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnSharedLibraries() {
			for (CppSharedLibrary binary : project.getComponents().withType(CppSharedLibrary.class)) {
				assertThat(() -> subject.runtimeElementsOf(binary), doesNotThrowException());
				assertThat(subject.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(subject.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessRuntimeElementsConfigurationOnExecutables() {
			for (CppExecutable binary : project.getComponents().withType(CppExecutable.class)) {
				assertThat(() -> subject.runtimeElementsOf(binary), doesNotThrowException());
				assertThat(subject.runtimeElementsOf(binary), named(runtimeElementsConfigurationName(binary)));
				assertThat(subject.runtimeElementsOf(binary), providerOf(named(runtimeElementsConfigurationName(binary))));
			}
		}

		@Test
		void canAccessCompileTaskOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> subject.compileTaskOf(binary), doesNotThrowException());
				assertThat(subject.compileTaskOf(binary), named(compileTaskName(binary)));
				assertThat(subject.compileTaskOf(binary), providerOf(named(compileTaskName(binary))));
			}
		}

		@Test
		void canAccessLinkTaskOnExecutables() {
			for (ComponentWithExecutable binary : project.getComponents().withType(ComponentWithExecutable.class)) {
				assertThat(() -> subject.linkTaskOf(binary), doesNotThrowException());
				assertThat(subject.linkTaskOf(binary), named(linkTaskName((CppBinary) binary)));
				assertThat(subject.linkTaskOf(binary), providerOf(named(linkTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessLinkTaskOnSharedLibraries() {
			for (ComponentWithSharedLibrary binary : project.getComponents().withType(ComponentWithSharedLibrary.class)) {
				assertThat(() -> subject.linkTaskOf(binary), doesNotThrowException());
				assertThat(subject.linkTaskOf(binary), named(linkTaskName((CppBinary) binary)));
				assertThat(subject.linkTaskOf(binary), providerOf(named(linkTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessInstallTaskOnExecutables() {
			for (ComponentWithInstallation binary : project.getComponents().withType(ComponentWithInstallation.class)) {
				assertThat(() -> subject.installTaskOf(binary), doesNotThrowException());
				assertThat(subject.installTaskOf(binary), named(installTaskName((CppBinary) binary)));
				assertThat(subject.installTaskOf(binary), providerOf(named(installTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessCreateTaskOnStaticLibraries() {
			for (ComponentWithStaticLibrary binary : project.getComponents().withType(ComponentWithStaticLibrary.class)) {
				assertThat(() -> subject.createTaskOf(binary), doesNotThrowException());
				assertThat(subject.createTaskOf(binary), named(createTaskName((CppBinary) binary)));
				assertThat(subject.createTaskOf(binary), providerOf(named(createTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessRunTaskOnTestExecutables() {
			for (CppTestExecutable binary : project.getComponents().withType(CppTestExecutable.class)) {
				assertThat(() -> subject.runTaskOf(binary), doesNotThrowException());
				assertThat(subject.runTaskOf(binary), named(runTaskName(binary)));
				assertThat(subject.runTaskOf(binary), providerOf(named(runTaskName(binary))));
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
