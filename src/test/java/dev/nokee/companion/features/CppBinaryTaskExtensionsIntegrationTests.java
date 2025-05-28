package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.SkipWhenNoSubject;
import dev.nokee.commons.fixtures.Subject;
import dev.nokee.commons.fixtures.SubjectExtension;
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
import static dev.nokee.companion.CppBinaryTaskExtensions.*;
import static org.hamcrest.MatcherAssert.assertThat;

@ExtendWith(SubjectExtension.class)
class CppBinaryTaskExtensionsIntegrationTests {
	@Subject Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	interface Tester {
		@Test
		default void canAccessCompileTaskOnBinaries(@Subject CppBinary binary) {
			assertThat(() -> compileTask(binary), doesNotThrowException());
			assertThat(compileTask(binary), named(compileTaskName(binary)));
			assertThat(compileTask(binary), providerOf(named(compileTaskName(binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkTaskOnExecutables(@Subject ComponentWithExecutable binary) {
			assertThat(() -> linkTask(binary), doesNotThrowException());
			assertThat(linkTask(binary), named(linkTaskName((CppBinary) binary)));
			assertThat(linkTask(binary), providerOf(named(linkTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessLinkTaskOnSharedLibraries(@Subject ComponentWithSharedLibrary binary) {
			assertThat(() -> linkTask(binary), doesNotThrowException());
			assertThat(linkTask(binary), named(linkTaskName((CppBinary) binary)));
			assertThat(linkTask(binary), providerOf(named(linkTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessInstallTaskOnExecutables(@Subject ComponentWithInstallation binary) {
			assertThat(() -> installTask(binary), doesNotThrowException());
			assertThat(installTask(binary), named(installTaskName((CppBinary) binary)));
			assertThat(installTask(binary), providerOf(named(installTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessCreateTaskOnStaticLibraries(@Subject ComponentWithStaticLibrary binary) {
			assertThat(() -> createTask(binary), doesNotThrowException());
			assertThat(createTask(binary), named(createTaskName((CppBinary) binary)));
			assertThat(createTask(binary), providerOf(named(createTaskName((CppBinary) binary))));
		}

		@Test
		@SkipWhenNoSubject
		default void canAccessRunTaskOnTestExecutables(@Subject CppTestExecutable binary) {
			assertThat(() -> runTask(binary), doesNotThrowException());
			assertThat(runTask(binary), named(runTaskName(binary)));
			assertThat(runTask(binary), providerOf(named(runTaskName(binary))));
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
