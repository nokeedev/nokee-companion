package dev.nokee.companion.features;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
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
import static dev.nokee.companion.CppBinaryTaskExtensions.*;
import static org.hamcrest.MatcherAssert.assertThat;

class CppBinaryTaskExtensionsIntegrationTests {
	Project project;

	@BeforeEach
	void setup(@TempDir Path testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	abstract class Tester {
		@Test
		void canAccessCompileTaskOnBinaries() {
			for (CppBinary binary : project.getComponents().withType(CppBinary.class)) {
				assertThat(() -> compileTask(binary), doesNotThrowException());
				assertThat(compileTask(binary), named(compileTaskName(binary)));
				assertThat(compileTask(binary), providerOf(named(compileTaskName(binary))));
			}
		}

		@Test
		void canAccessLinkTaskOnExecutables() {
			for (ComponentWithExecutable binary : project.getComponents().withType(ComponentWithExecutable.class)) {
				assertThat(() -> linkTask(binary), doesNotThrowException());
				assertThat(linkTask(binary), named(linkTaskName((CppBinary) binary)));
				assertThat(linkTask(binary), providerOf(named(linkTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessLinkTaskOnSharedLibraries() {
			for (ComponentWithSharedLibrary binary : project.getComponents().withType(ComponentWithSharedLibrary.class)) {
				assertThat(() -> linkTask(binary), doesNotThrowException());
				assertThat(linkTask(binary), named(linkTaskName((CppBinary) binary)));
				assertThat(linkTask(binary), providerOf(named(linkTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessInstallTaskOnExecutables() {
			for (ComponentWithInstallation binary : project.getComponents().withType(ComponentWithInstallation.class)) {
				assertThat(() -> installTask(binary), doesNotThrowException());
				assertThat(installTask(binary), named(installTaskName((CppBinary) binary)));
				assertThat(installTask(binary), providerOf(named(installTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessCreateTaskOnStaticLibraries() {
			for (ComponentWithStaticLibrary binary : project.getComponents().withType(ComponentWithStaticLibrary.class)) {
				assertThat(() -> createTask(binary), doesNotThrowException());
				assertThat(createTask(binary), named(createTaskName((CppBinary) binary)));
				assertThat(createTask(binary), providerOf(named(createTaskName((CppBinary) binary))));
			}
		}

		@Test
		void canAccessRunTaskOnTestExecutables() {
			for (CppTestExecutable binary : project.getComponents().withType(CppTestExecutable.class)) {
				assertThat(() -> runTask(binary), doesNotThrowException());
				assertThat(runTask(binary), named(runTaskName(binary)));
				assertThat(runTask(binary), providerOf(named(runTaskName(binary))));
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
