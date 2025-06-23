package dev.nokee.companion.features;

import dev.nokee.commons.fixtures.SourceOptionsAwareIntegrationTester;
import dev.nokee.commons.fixtures.Subject;
import dev.nokee.elements.core.GradleLayoutElement;
import dev.nokee.language.nativebase.tasks.options.NativeCompileOptions;
import dev.nokee.templates.CppApp;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static dev.nokee.elements.core.ProjectElement.ofMain;

class CppCompileSourceOptionsIntegrationTests extends SourceOptionsAwareIntegrationTester<NativeCompileOptions> {
	@Subject Project project;
	@TempDir Path testDirectory;
	@Subject CppCompileTask compileTask;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply(NativeComponentPlugin.class);
		((ProjectInternal) project).getModelRegistry().realize("toolChains", NativeToolChainRegistry.class);

		compileTask = project.getTasks().create("compile", CppCompileTask.class);
		compileTask.getTargetPlatform().set(DefaultNativePlatform.host());
		compileTask.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("gcc"));

		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(testDirectory);
	}

	@Override
	protected File file1() {
		return testDirectory.resolve("src/main/cpp/message.cpp").toFile();
	}

	@Override
	protected File file2() {
		return testDirectory.resolve("src/main/cpp/main.cpp").toFile();
	}

	@Override
	protected MissingTestFile missingFile() {
		final Path value = testDirectory.resolve("src/main/cpp/missing-source.cpp");
		return new MissingTestFile() {
			public File toFile() {
				return value.toFile();
			}
		};
	}

	@Override
	protected MissingTestDirectory missingDirectory() {
		final Path value = testDirectory.resolve("src/main/cpp/missing-dir");
		return new MissingTestDirectory() {
			@Override
			public File subFile() {
				return value.resolve("foo.cpp").toFile();
			}

			@Override
			public File toFile() {
				return value.toFile();
			}
		};
	}
}
