package dev.nokee.companion.features;

import com.google.common.reflect.TypeToken;
import dev.nokee.commons.fixtures.Subject;
import dev.nokee.commons.fixtures.SubjectExtension;
import dev.nokee.companion.CppBinaryTaskExtensions;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.*;
import org.gradle.language.nativeplatform.ComponentWithInstallation;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.testfixtures.ProjectBuilder;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import static dev.nokee.commons.hamcrest.With.with;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ProjectMatchers.extension;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@ExtendWith(SubjectExtension.class)
class CppBinaryTaskExtensionsIntegrationTests {
	@TempDir Path testDirectory;

	private static <T extends CppBinary> Matcher<TestProject<T>.BinaryView> canAccess(Function<? super T, ? extends TaskProvider<?>> mapper) {
		return new FeatureMatcher<TestProject<T>.BinaryView, List<TaskProvider<?>>>(not(emptyIterable()), "", "") {
			@Override
			protected List<TaskProvider<?>> featureValueOf(TestProject<T>.BinaryView actual) {
				final List<TaskProvider<?>> result = new ArrayList<>();
				actual.configureEach(it -> result.add(mapper.apply((T) it)));
				actual.finalizeNow();
				return result;
			}
		};
	}

	private static final class TestProject<B extends CppBinary> {
		private final Project project;

		public TestProject(Project project) {
			this.project = project;
		}

		abstract class BinaryView implements Iterable<B> {
			public abstract void configureEach(Action<? super B> action);

			final void finalizeNow() {
				((ProjectInternal) project).evaluate();
			}
		}

		private Class<B> binaryType() {
			return (Class<B>) new TypeToken<B>(getClass()) {}.getRawType();
		}

		public BinaryView getProjectBinaries() {
			return new BinaryView() {
				@Override
				public void configureEach(Action<? super B> action) {
					project.getComponents().withType(binaryType()).configureEach(action);
				}

				@Override
				public Iterator<B> iterator() {
					finalizeNow();
					return project.getComponents().withType(binaryType()).iterator();
				}
			};
		}

		public BinaryView getComponentBinaries() {
			return new BinaryView() {
				@Override
				public void configureEach(Action<? super B> action) {
					project.getExtensions().getByType(CppComponent.class).getBinaries().configureEach(binaryType(), action);
				}

				@Override
				public Iterator<B> iterator() {
					finalizeNow();
					return project.getExtensions().getByType(CppComponent.class).getBinaries().get().stream().map(binaryType()::cast).iterator();
				}
			};
		}
	}

	private interface AccessCompileTaskTester {
		@Test
		default void canAccessCompileTaskOnProjectBinaries(@Subject TestProject<? extends CppBinary> project) {
			assertThat(project.getProjectBinaries(), canAccess(CppBinaryTaskExtensions::compileTask));
		}

		@Test
		default void canAccessCompileTaskOnComponentBinaries(@Subject TestProject<? extends CppBinary> project) {
			assertThat(project.getComponentBinaries(), canAccess(CppBinaryTaskExtensions::compileTask));
		}
	}

	private interface AccessLinkTaskTester<BinaryType extends CppBinary> {
		TaskProvider<?> linkTask(BinaryType binary);

		@Test
		default void canAccessLinkTaskOnProjectBinaries(@Subject TestProject<BinaryType> project) {
			assertThat(project.getProjectBinaries(), canAccess(this::linkTask));
		}

		@Test
		default void canAccessLinkTaskOnComponentBinaries(@Subject TestProject<BinaryType> project) {
			assertThat(project.getComponentBinaries(), canAccess(this::linkTask));
		}
	}

	private interface AccessInstallTaskTester {
		@Test
		default void canAccessInstallTaskOnProjectBinaries(@Subject TestProject<? extends ComponentWithInstallation> project) {
			assertThat(project.getProjectBinaries(), canAccess(CppBinaryTaskExtensions::installTask));
		}

		@Test
		default void canAccessInstallTaskOnComponentBinaries(@Subject TestProject<? extends ComponentWithInstallation> project) {
			assertThat(project.getComponentBinaries(), canAccess(CppBinaryTaskExtensions::installTask));
		}
	}

	@Nested
	class WhenCppApplicationPluginApplied implements AccessCompileTaskTester, AccessLinkTaskTester<CppExecutable>, AccessInstallTaskTester {
		@Subject
		TestProject<CppExecutable> newSubject() {
			Project project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
			project.getPlugins().apply("dev.nokee.native-companion");
			project.getPlugins().apply("cpp-application");
			return new TestProject<>(project);
		}

		@Test
		void noCreateTask(@Subject TestProject<CppExecutable> project) {
			assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("createTask"))))));
		}

		@Test
		void noRunTask(@Subject TestProject<CppExecutable> project) {
			assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("runTask"))))));
		}

		@Override
		public TaskProvider<?> linkTask(CppExecutable binary) {
			return CppBinaryTaskExtensions.linkTask(binary);
		}
	}

	private interface AccessCreateTaskTester {
		@Test
		default void canAccessCreateTaskOnProjectBinaries(@Subject TestProject<CppStaticLibrary> project) {
			assertThat(project.getProjectBinaries(), canAccess(CppBinaryTaskExtensions::createTask));
		}

		@Test
		default void canAccessCreateTaskOnComponentBinaries(@Subject TestProject<CppStaticLibrary> project) {
			assertThat(project.getComponentBinaries(), canAccess(CppBinaryTaskExtensions::createTask));
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied {
		@Nested
		class StaticLibraries implements AccessCompileTaskTester, AccessCreateTaskTester {
			@Subject TestProject<CppStaticLibrary> newSubject() {
				Project project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
				project.getPlugins().apply("dev.nokee.native-companion");
				project.getPlugins().apply("cpp-library");
				project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.STATIC));
				return new TestProject<>(project);
			}

			@Test
			void noLinkTask(@Subject TestProject<CppStaticLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("linkTask"))))));
			}

			@Test
			void noInstallTask(@Subject TestProject<CppStaticLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("installTask"))))));
			}

			@Test
			void noRunTask(@Subject TestProject<CppStaticLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("runTask"))))));
			}
		}

		@Nested
		class SharedLibraries implements AccessCompileTaskTester, AccessLinkTaskTester<CppSharedLibrary> {
			@Subject TestProject<CppSharedLibrary> newSubject() {
				Project project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
				project.getPlugins().apply("dev.nokee.native-companion");
				project.getPlugins().apply("cpp-library");
				project.getExtensions().getByType(CppLibrary.class).getLinkage().set(List.of(Linkage.SHARED));
				return new TestProject<>(project);
			}

			@Test
			void noCreateTask(@Subject TestProject<CppSharedLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("createTask"))))));
			}

			@Test
			void noInstallTask(@Subject TestProject<CppSharedLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("installTask"))))));
			}

			@Test
			void noRunTask(@Subject TestProject<CppSharedLibrary> project) {
				assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("runTask"))))));
			}

			@Override
			public TaskProvider<?> linkTask(CppSharedLibrary binary) {
				return CppBinaryTaskExtensions.linkTask(binary);
			}
		}
	}

	private interface AccessRunTaskTester {
		@Test
		default void canAccessRunTaskOnProjectBinaries(@Subject TestProject<CppTestExecutable> project) {
			assertThat(project.getProjectBinaries(), canAccess(CppBinaryTaskExtensions::installTask));
		}

		@Test
		default void canAccessRunTaskOnComponentBinaries(@Subject TestProject<CppTestExecutable> project) {
			assertThat(project.getComponentBinaries(), canAccess(CppBinaryTaskExtensions::installTask));
		}
	}

	@Nested
	class WhenCppUnitTestPluginApplied implements AccessCompileTaskTester, AccessLinkTaskTester<CppTestExecutable>, AccessInstallTaskTester, AccessRunTaskTester {
		@Subject TestProject<CppTestExecutable> newSubject() {
			Project project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
			project.getPlugins().apply("dev.nokee.native-companion");
			project.getPlugins().apply("cpp-unit-test");
			return new TestProject<>(project);
		}

		@Test
		void noCreateTask(@Subject TestProject<CppTestExecutable> project) {
			assertThat(project.getProjectBinaries(), everyItem(with(not(extension(named("createTask"))))));
		}

		@Override
		public TaskProvider<?> linkTask(CppTestExecutable binary) {
			return CppBinaryTaskExtensions.linkTask(binary);
		}
	}
}
