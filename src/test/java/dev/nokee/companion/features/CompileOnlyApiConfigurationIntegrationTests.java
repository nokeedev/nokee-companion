package dev.nokee.companion.features;

import dev.nokee.companion.CppEcosystemUtilities;
import org.gradle.api.Project;
import org.gradle.language.cpp.CppLibrary;
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

class CompileOnlyApiConfigurationIntegrationTests {
	Project project;

	@BeforeEach
	void setup(@TempDir File testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory).build();
		project.getPluginManager().apply("dev.nokee.native-companion");
	}

	@Test
	void hasNoCompileOnlyBucketWhenUnmatchedCppComponents() {
		assertThat(project.getConfigurations().findByName("compileOnlyApi"), is(nullValue()));
		assertThat(project.getConfigurations().findByName("testCompileOnlyApi"), is(nullValue()));
	}

	@Test
	void hasNoCompileOnlyApiBucketForCppApplication() {
		project.getPluginManager().apply("cpp-application");

		assertThat(project.getConfigurations().findByName("compileOnlyApi"), is(nullValue()));
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
		void hasCompileOnlyApiBucketForCppLibrary() {
			assertThat(project.getConfigurations().findByName("compileOnlyApi"), is(notNullValue()));
		}

		@Test
		void hasCompileOnlyApiBucketForCppLibraryUsingUtilities() {
			assertThat(forProject(project).compileOnlyOf(subject), providerOf(named("compileOnlyApi")));
		}
	}

	@Test
	void hasNoCompileOnlyApiBucketForCppUnitTest() {
		project.getPluginManager().apply("cpp-unit-test");

		assertThat(project.getConfigurations().findByName("compileOnlyApi"), is(nullValue()));
		assertThat(project.getConfigurations().findByName("testCompileOnlyApi"), is(nullValue()));
	}
}
