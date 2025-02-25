package dev.nokee.companion.features;

import dev.nokee.companion.CppBinaryTaskExtensions;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.*;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.nokee.commons.hamcrest.With.with;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ProjectMatchers.extension;
import static dev.nokee.commons.hamcrest.gradle.ProjectMatchers.publicType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CppBinaryTaskExtensionsIntegrationTests {
	Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	@Nested
	class WhenCppApplicationPluginApplied {
		List<CppExecutable> binaries;

		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-application");
			((ProjectInternal) project).evaluate();
			binaries = project.getExtensions().getByType(CppApplication.class).getBinaries().get().stream().map(CppExecutable.class::cast).toList();
			assertThat(binaries, hasSize(2));
		}

		@Test
		void canAccessCompileTask() {
			assertThat(binaries, everyItem(with(extension(named("compileTask"), publicType(new TypeOf<TaskProvider<CppCompile>>() {})))));
			assertThat(binaries.stream().map(CppBinaryTaskExtensions::compileTask).toList(), contains(named("compileDebugCpp"), named("compileReleaseCpp")));
		}

		@Test
		void canAccessLinkTask() {
			assertThat(binaries, everyItem(with(extension(named("linkTask"), publicType(new TypeOf<TaskProvider<LinkExecutable>>() {})))));
			assertThat(binaries.stream().map(CppBinaryTaskExtensions::linkTask).toList(), contains(named("linkDebug"), named("linkRelease")));
		}

		@Test
		void canAccessInstallTask() {
			assertThat(binaries, everyItem(with(extension(named("installTask"), publicType(new TypeOf<TaskProvider<InstallExecutable>>() {})))));
			assertThat(binaries.stream().map(CppBinaryTaskExtensions::installTask).toList(), contains(named("installDebug"), named("installRelease")));
		}

		@Test
		void noCreateTask() {
			assertThat(binaries, everyItem(with(not(extension(named("createTask"))))));
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied {

		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-library");
		}

		@Nested
		class StaticLibraries {
			List<CppStaticLibrary> binaries;

			@BeforeEach
			void setup() {
				project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.STATIC));
				((ProjectInternal) project).evaluate();
				binaries = project.getExtensions().getByType(CppLibrary.class).getBinaries().get().stream().map(CppStaticLibrary.class::cast).toList();
				assertThat(binaries, hasSize(2));
			}

			@Test
			void canAccessCompileTask() {
				assertThat(binaries, everyItem(with(extension(named("compileTask"), publicType(new TypeOf<TaskProvider<CppCompile>>() {})))));
				assertThat(binaries.stream().map(CppBinaryTaskExtensions::compileTask).toList(), contains(named("compileDebugCpp"), named("compileReleaseCpp")));
			}

			@Test
			void canAccessLinkTask() {
				assertThat(binaries, everyItem(with(extension(named("createTask"), publicType(new TypeOf<TaskProvider<CreateStaticLibrary>>() {})))));
				assertThat(binaries.stream().map(CppBinaryTaskExtensions::createTask).toList(), contains(named("createDebug"), named("createRelease")));
			}

			@Test
			void noLinkTask() {
				assertThat(binaries, everyItem(with(not(extension(named("linkTask"))))));
			}

			@Test
			void noInstallTask() {
				assertThat(binaries, everyItem(with(not(extension(named("installTask"))))));
			}
		}

		@Nested
		class SharedLibraries {
			List<CppSharedLibrary> binaries;

			@BeforeEach
			void setup() {
				project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.SHARED));
				((ProjectInternal) project).evaluate();
				binaries = project.getExtensions().getByType(CppLibrary.class).getBinaries().get().stream().map(CppSharedLibrary.class::cast).toList();
				assertThat(binaries, hasSize(2));
			}

			@Test
			void canAccessCompileTask() {
				assertThat(binaries, everyItem(with(extension(named("compileTask"), publicType(new TypeOf<TaskProvider<CppCompile>>() {})))));
				assertThat(binaries.stream().map(CppBinaryTaskExtensions::compileTask).toList(), contains(named("compileDebugCpp"), named("compileReleaseCpp")));
			}

			@Test
			void canAccessLinkTask() {
				assertThat(binaries, everyItem(with(extension(named("linkTask"), publicType(new TypeOf<TaskProvider<LinkSharedLibrary>>() {})))));
				assertThat(binaries.stream().map(CppBinaryTaskExtensions::linkTask).toList(), contains(named("linkDebug"), named("linkRelease")));
			}

			@Test
			void noCreateTask() {
				assertThat(binaries, everyItem(with(not(extension(named("createTask"))))));
			}

			@Test
			void noInstallTask() {
				assertThat(binaries, everyItem(with(not(extension(named("installTask"))))));
			}
		}
	}
}
