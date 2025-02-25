package dev.nokee.companion.features;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.provider.Provider;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.*;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.provider.NoValueProviderMatcher.noValueProvider;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.companion.util.CopyFromAction.copyFrom;
import static java.nio.file.Files.createFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class LinkExecutableCopyFromIntegrationTests {
	Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply(NativeComponentPlugin.class);
		((ProjectInternal) project).getModelRegistry().realize("toolChains", NativeToolChainRegistry.class);
	}

	private Provider<LinkExecutable> coreLinkTask() {
		return project.getTasks().register("link", LinkExecutable.class, task -> {
			task.getTargetPlatform().set(DefaultNativePlatform.host());
			task.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("gcc"));
			task.getDebuggable().set(true);
			task.getLibs().from("/usr/lib/libfoo.so");
			task.getLinkedFile().set(project.getLayout().getBuildDirectory().file("exes/a.out"));
			task.getLinkerArgs().add("--some-flag");
			try {
				task.source(createFile(project.file("file1.o").toPath()));
				task.source(createFile(project.file("file2.o").toPath()));
				task.source(createFile(project.file("file3.o").toPath()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	@Test
	void canCopyFromCoreToCoreLinkTask() {
		LinkExecutable subject = project.getTasks().create("test", LinkExecutable.class, copyFrom(coreLinkTask()));

		subject.getTaskDependencies().getDependencies(null);

		assertThat(subject.isDebuggable(), is(true));
		assertThat(subject.getDebuggable(), providerOf(true));
		assertThat(subject.getToolChain(), providerOf(allOf(named("gcc"), instanceOf(Gcc.class))));
		assertThat(subject.getTargetPlatform(), providerOf(instanceOf(NativePlatform.class)));
		assertThat(subject.getLinkerArgs(), providerOf(contains("--some-flag")));
		assertThat(subject.getSource(), contains(aFileNamed("file1.o"), aFileNamed("file2.o"), aFileNamed("file3.o")));
		assertThat(subject.getLibs(), contains(aFileNamed("libfoo.so")));
		assertThat(subject.getDestinationDirectory(), noValueProvider());
		assertThat(subject.getLinkedFile(), noValueProvider());
	}
}
