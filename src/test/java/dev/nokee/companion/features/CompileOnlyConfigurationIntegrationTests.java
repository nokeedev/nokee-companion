package dev.nokee.companion.features;

import org.gradle.api.Project;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.companion.CppEcosystemUtilities.forProject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CompileOnlyConfigurationIntegrationTests {
	Project project;

	@BeforeEach
	void setup(@TempDir File testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory).build();
		project.getPluginManager().apply("dev.nokee.native-companion");
	}

	@Test
	void hasNoCompileOnlyBucketWhenUnmatchedCppComponents() {
		assertThat(project.getConfigurations().findByName("compileOnly"), is(nullValue()));
		assertThat(project.getConfigurations().findByName("testCompileOnly"), is(nullValue()));
	}

	@Nested
	class WhenCppApplicationPluginApplied {
		CppApplication subject;

		@BeforeEach
		void setup() {
			project.getPluginManager().apply("cpp-application");
			subject = project.getExtensions().getByType(CppApplication.class);
		}

		@Test
		void hasCompileOnlyBucketForCppApplication() {
			assertThat(project.getConfigurations().findByName("compileOnly"), is(notNullValue()));
		}

		@Test
		void hasCompileOnlyBucketForCppApplicationUsingUtilities() {
			assertThat(forProject(project).compileOnlyOf(subject), providerOf(named("compileOnly")));
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied {
		CppLibrary subject;

		@BeforeEach
		void setup() {
			project.getPluginManager().apply("cpp-library");
			subject = project.getExtensions().getByType(CppLibrary.class);
		}

		@Test
		void hasCompileOnlyBucketForCppLibrary() {
			assertThat(project.getConfigurations().findByName("compileOnly"), is(notNullValue()));
		}

		@Test
		void hasCompileOnlyBucketForCppLibraryUsingUtilities() {
			assertThat(forProject(project).compileOnlyOf(subject), providerOf(named("compileOnly")));
		}
	}

	@Nested
	class WhenCppUnitTestPluginApplied {
		CppTestSuite subject;

		@BeforeEach
		void setup() {
			project.getPluginManager().apply("cpp-unit-test");
			subject = project.getExtensions().getByType(CppTestSuite.class);
		}

		@Test
		void hasCompileOnlyBucketForCppUnitTest() {
			assertThat(project.getConfigurations().findByName("compileOnly"), is(nullValue()));
			assertThat(project.getConfigurations().findByName("testCompileOnly"), is(notNullValue()));
		}

		@Test
		void hasCompileOnlyBucketForCppUnitTestUsingUtilities() {
			assertThat(forProject(project).compileOnlyOf(subject), providerOf(named("testCompileOnly")));
		}
	}
}
