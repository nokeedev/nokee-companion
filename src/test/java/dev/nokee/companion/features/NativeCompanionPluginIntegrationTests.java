package dev.nokee.companion.features;

import dev.nokee.companion.NativeCompanionExtension;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.nokee.commons.hamcrest.Has.has;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.ProjectMatchers.extension;
import static dev.nokee.commons.hamcrest.gradle.ProjectMatchers.publicType;
import static dev.nokee.companion.NativeCompanionExtension.nativeCompanionOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class NativeCompanionPluginIntegrationTests {
	Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
	}

	@Test
	void registerNativeCompanionExtension() {
		assertThat(project, has(extension(named("nativeCompanion"), publicType(NativeCompanionExtension.class))));
	}

	@Test
	void canRetrieveExtensionViaJavaApi() {
		assertThat(nativeCompanionOf(project), equalTo(project.getExtensions().getByName("nativeCompanion")));
	}
}
