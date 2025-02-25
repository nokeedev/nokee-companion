package dev.nokee.companion.features;

import dev.nokee.commons.gradle.tasks.options.SourceOptionsAware;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.nokee.commons.fixtures.ActionTestUtils.doSomething;
import static dev.nokee.commons.fixtures.ActionTestUtils.doSomethingElse;
import static dev.nokee.commons.fixtures.SourceOptionsMatchers.sourceFile;
import static dev.nokee.commons.hamcrest.With.with;
import static dev.nokee.commons.hamcrest.gradle.FileSystemMatchers.*;
import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.provider.NoValueProviderMatcher.noValueProvider;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.companion.util.CopyFromAction.copyFrom;
import static java.nio.file.Files.createFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CppCompileCopyFromIntegrationTests {
	Project project;
	@TempDir Path testDirectory;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply(NativeComponentPlugin.class);
		((ProjectInternal) project).getModelRegistry().realize("toolChains", NativeToolChainRegistry.class);
	}

	private Provider<CppCompile> nokeeCompileTask() {
		return project.getTasks().register("compile", CppCompileTask.clazz(), task -> {
			task.getTargetPlatform().set(DefaultNativePlatform.host());
			task.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("gcc"));
			task.getOptions().getDebuggable().set(true);
			task.getOptions().getPositionIndependentCode().set(true);
			task.getOptions().getOptimized().set(true);
			task.getOptions().getPreprocessorOptions().getDefinedMacros().put("MY_MACRO", "foo");
			task.getOptions().getPreprocessorOptions().getDefinedMacros().put("MY_OTHER_MACRO", "bar");
			task.getOptions().getIncrementalAfterFailure().set(true);
			task.getSystemIncludes().from("/usr/includes");
			task.getIncludes().from(project.file("my-includes"));
			task.getCompilerArgs().add("--some-flag");
			try {
				task.source(createFile(project.file("file1.cpp").toPath()));
				task.source(createFile(project.file("file2.cpp").toPath()), doSomething());
				task.source(createFile(project.file("file3.cpp").toPath()), doSomethingElse());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private Provider<org.gradle.language.cpp.tasks.CppCompile> coreCompileTask() {
		return project.getTasks().register("compile", org.gradle.language.cpp.tasks.CppCompile.class, task -> {
			task.getTargetPlatform().set(DefaultNativePlatform.host());
			task.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("gcc"));
			task.setDebuggable(true);
			task.setPositionIndependentCode(true);
			task.setOptimized(true);
			task.getMacros().put("MY_MACRO", "foo");
			task.getMacros().put("MY_OTHER_MACRO", "bar");
			task.getSystemIncludes().from("/usr/includes");
			task.getIncludes().from(project.file("my-includes"));
			task.getCompilerArgs().add("--some-flag");
			try {
				task.source(createFile(project.file("file1.cpp").toPath()));
				task.source(createFile(project.file("file2.cpp").toPath()));
				task.source(createFile(project.file("file3.cpp").toPath()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	@Test
	void canCopyFromNokeeToNokeeCppCompileTask() {
		CppCompile subject = project.getTasks().create("test", CppCompile.clazz(), copyFrom(nokeeCompileTask()));
		assertThat(subject.isDebuggable(), is(true));
		assertThat(subject.isPositionIndependentCode(), is(true));
		assertThat(subject.isOptimized(), is(true));
		assertThat(subject.getMacros(), allOf(aMapWithSize(2), hasEntry("MY_MACRO", "foo"), hasEntry("MY_OTHER_MACRO", "bar")));
		assertThat(subject.getOptions().getIncrementalAfterFailure(), providerOf(true));
		assertThat(subject.getSystemIncludes(), contains(aFile(with(absolutePath("/usr/includes")))));
		assertThat(subject.getToolChain(), providerOf(allOf(named("gcc"), instanceOf(Gcc.class))));
		assertThat(subject.getTargetPlatform(), providerOf(instanceOf(NativePlatform.class)));
		assertThat(subject.getCompilerArgs(), providerOf(contains("--some-flag")));
		assertThat(subject.getSource(), contains(aFileNamed("file1.cpp"), aFileNamed("file2.cpp"), aFileNamed("file3.cpp")));
		assertThat(subject.getIncludes(), contains(aFileNamed("my-includes")));
		assertThat(((SourceOptionsAware.Options<?>) subject.getOptions()).forAllSources(), providerOf(contains(with(sourceFile(aFileNamed("file1.cpp"))), with(sourceFile(aFileNamed("file2.cpp"))), with(sourceFile(aFileNamed("file3.cpp"))))));
	}

	@Test
	void canCopyFromNokeeToCoreCppCompileTask() {
		org.gradle.language.cpp.tasks.CppCompile subject = project.getTasks().create("test", org.gradle.language.cpp.tasks.CppCompile.class, copyFrom(nokeeCompileTask()));

		subject.getTaskDependencies().getDependencies(null);

		assertThat(subject.isDebuggable(), is(true));
		assertThat(subject.isPositionIndependentCode(), is(true));
		assertThat(subject.isOptimized(), is(true));
		assertThat(subject.getMacros(), anEmptyMap());
		assertThat(subject.getSystemIncludes(), contains(aFile(with(absolutePath("/usr/includes")))));
		assertThat(subject.getToolChain(), providerOf(allOf(named("gcc"), instanceOf(Gcc.class))));
		assertThat(subject.getTargetPlatform(), providerOf(instanceOf(NativePlatform.class)));
		assertThat(subject.getCompilerArgs(), providerOf(contains("-DMY_MACRO=foo", "-DMY_OTHER_MACRO=bar", "--some-flag")));
		assertThat(subject.getSource(), contains(aFileNamed("file1.cpp"), aFileNamed("file2.cpp"), aFileNamed("file3.cpp")));
		assertThat(subject.getIncludes(), contains(aFileNamed("my-includes")));
	}

	@Test
	void canCopyFromCoreToNokeeCppCompileTask() {
		CppCompile subject = project.getTasks().create("test", CppCompile.clazz(), copyFrom(coreCompileTask()));
		assertThat(subject.isDebuggable(), is(true));
		assertThat(subject.isPositionIndependentCode(), is(true));
		assertThat(subject.isOptimized(), is(true));
		assertThat(subject.getMacros(), allOf(aMapWithSize(2), hasEntry("MY_MACRO", "foo"), hasEntry("MY_OTHER_MACRO", "bar")));
		assertThat(subject.getOptions().getIncrementalAfterFailure(), noValueProvider());
		assertThat(subject.getSystemIncludes(), contains(aFile(with(absolutePath("/usr/includes")))));
		assertThat(subject.getToolChain(), providerOf(allOf(named("gcc"), instanceOf(Gcc.class))));
		assertThat(subject.getTargetPlatform(), providerOf(instanceOf(NativePlatform.class)));
		assertThat(subject.getCompilerArgs(), providerOf(contains("--some-flag")));
		assertThat(subject.getSource(), contains(aFileNamed("file1.cpp"), aFileNamed("file2.cpp"), aFileNamed("file3.cpp")));
		assertThat(subject.getIncludes(), contains(aFileNamed("my-includes")));
		assertThat(((SourceOptionsAware.Options<?>) subject.getOptions()).forAllSources(), providerOf(contains(with(sourceFile(aFileNamed("file1.cpp"))), with(sourceFile(aFileNamed("file2.cpp"))), with(sourceFile(aFileNamed("file3.cpp"))))));
	}

	@Test
	void canCopyFromCoreToCoreCppCompileTask() {
		org.gradle.language.cpp.tasks.CppCompile subject = project.getTasks().create("test", org.gradle.language.cpp.tasks.CppCompile.class, copyFrom(coreCompileTask()));

		subject.getTaskDependencies().getDependencies(null);

		assertThat(subject.isDebuggable(), is(true));
		assertThat(subject.isPositionIndependentCode(), is(true));
		assertThat(subject.isOptimized(), is(true));
		assertThat(subject.getMacros(), anEmptyMap());
		assertThat(subject.getSystemIncludes(), contains(aFile(with(absolutePath("/usr/includes")))));
		assertThat(subject.getToolChain(), providerOf(allOf(named("gcc"), instanceOf(Gcc.class))));
		assertThat(subject.getTargetPlatform(), providerOf(instanceOf(NativePlatform.class)));
		assertThat(subject.getCompilerArgs(), providerOf(contains("-DMY_MACRO=foo", "-DMY_OTHER_MACRO=bar", "--some-flag")));
		assertThat(subject.getSource(), contains(aFileNamed("file1.cpp"), aFileNamed("file2.cpp"), aFileNamed("file3.cpp")));
		assertThat(subject.getIncludes(), contains(aFileNamed("my-includes")));
	}
}
