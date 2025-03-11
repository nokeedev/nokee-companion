package dev.nokee.companion.features;

import dev.gradleplugins.runnerkit.GradleExecutor;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.gradle.file.SourceFileVisitor;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.NativeCompanionExtension;
import dev.nokee.companion.ObjectFiles;
import dev.nokee.language.cpp.tasks.CppCompile;
import dev.nokee.templates.CppApp;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.assembler.tasks.Assemble;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ObjectFilesIntegrationTests {
	Project project;

	@BeforeEach
	void setup(@TempDir File testDirectory) {
		project = ProjectBuilder.builder().withProjectDir(testDirectory).build();
		project.getPluginManager().apply("dev.nokee.native-companion");
//		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("native-task-object-files-extension");

		project.getPlugins().apply(NativeComponentPlugin.class);
		((ProjectInternal) project).getModelRegistry().realize("toolChains", NativeToolChainRegistry.class);
	}

	@Test
	void dfd() throws IOException {
		GradleBuildElement build = GradleBuildElement.inDirectory(Files.createTempDirectory("gradle"));
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		new CppApp().writeToDirectory(build.getLocation());
		GradleRunner.create(GradleExecutor.gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().withTasks("compileReleaseCpp").build();

		FileUtils.copyDirectory(build.dir("build/obj/main/release").toFile(), project.getLayout().getBuildDirectory().dir("obj").get().getAsFile());

		new CppApp().writeToDirectory(project.getProjectDir().toPath());
		Task compileTask = project.getTasks().create("test", CppCompile.clazz(), task -> {
			task.getTargetPlatform().set(DefaultNativePlatform.host());
			task.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("clang"));
			task.source(project.fileTree("src/main/cpp"));
			task.getObjectFileDir().set(project.getLayout().getBuildDirectory().dir("obj"));
		});

		List<String> val = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val.add(it.getPath())));

		assertThat(val, containsInAnyOrder("60yemoco4lam131yntpgpbh2r/size.o", "5v08dtn7cszvzrlgdayn1y9u6/main.o", "f0c36b8y9kkzr0hshipmuavim/message.o", "d7rpxlv4rhfra2lzuvpri9lyj/get.o", "c2kofpfbdvl0ox5prbhyuwx8n/join.o", "7l1rrxf4ugu1brfnb9msa2y3i/remove.o", "8dasnq975rzjosysh2j5fktog/split.o", "9ryzqhmpa69of9utjiizgddi3/copy_ctor_assign.o", "57uiwkglely1v0krq64yn8ivd/add.o", "3gfmv4hei6kdj2kxpjtavx6sh/destructor.o"));

		Files.createFile(project.getProjectDir().toPath().resolve("build/obj/unrelated.o"));
		List<String> val2 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val2.add(it.getPath())));
		assertThat(val2, hasItem("unrelated.o"));

		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("native-task-object-files-extension");
		List<String> val3 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val2.add(it.getPath())));
		assertThat(val3, containsInAnyOrder("60yemoco4lam131yntpgpbh2r/size.o", "5v08dtn7cszvzrlgdayn1y9u6/main.o", "f0c36b8y9kkzr0hshipmuavim/message.o", "d7rpxlv4rhfra2lzuvpri9lyj/get.o", "c2kofpfbdvl0ox5prbhyuwx8n/join.o", "7l1rrxf4ugu1brfnb9msa2y3i/remove.o", "8dasnq975rzjosysh2j5fktog/split.o", "9ryzqhmpa69of9utjiizgddi3/copy_ctor_assign.o", "57uiwkglely1v0krq64yn8ivd/add.o", "3gfmv4hei6kdj2kxpjtavx6sh/destructor.o"));
	}

	@Test
	void assembly() throws IOException {
		GradleBuildElement build = GradleBuildElement.inDirectory(Files.createTempDirectory("gradle"));
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		build.getBuildFile().append(groovyDsl("tasks.withType(CppCompile).configureEach { source(fileTree('src/main/cpp')) }"));
		new CppApp().writeToDirectory(build.getLocation());
		Files.list(build.getLocation().resolve("src/main/cpp")).forEach(it -> {
			try {
				Files.move(it, it.getParent().resolve(it.getFileName().toString().replace(".cpp", ".s")));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		GradleRunner.create(GradleExecutor.gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().withTasks("compileReleaseCpp").build();

		FileUtils.copyDirectory(build.dir("build/obj/main/release").toFile(), project.getLayout().getBuildDirectory().dir("obj").get().getAsFile());

		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("native-task-object-files-extension");

		new CppApp().writeToDirectory(project.getProjectDir().toPath());
		Files.list(project.getProjectDir().toPath().resolve("src/main/cpp")).forEach(it -> {
			try {
				Files.move(it, Files.createDirectories(new File(it.getParent().toString().replace("/src/main/cpp/", "/src/main/asm/")).toPath()).resolve(it.getFileName().toString().replace(".cpp", ".s")));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		Task compileTask = project.getTasks().create("test", Assemble.class, task -> {
			task.getTargetPlatform().set(DefaultNativePlatform.host());
			task.getToolChain().set(project.getExtensions().getByType(NativeToolChainRegistry.class).getByName("clang"));
			task.source(project.fileTree("src/main/asm"));
			task.setObjectFileDir(project.getLayout().getBuildDirectory().dir("obj").get().getAsFile());
		});

		List<String> val = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val.add(it.getPath())));

		System.out.println(val);
		assertThat(val, containsInAnyOrder("dtubmbufzoqugb7g2nr2ajmku/destructor.o", "bla582uw7ghia6h1j5306pigm/split.o", "5kossxetgzj7fykg9wq5u6rim/remove.o", "4xpvjqqo73v04ubildcqrv82l/copy_ctor_assign.o", "82e27d079y943zrk1pys1e2cn/size.o", "2f8iuoasryejxzyrn8m7wmkdv/join.o", "ev2fdlw45ba56yzx20i3maz1/message.o", "a3xkqa2f1a1r968dt6b5mdfl5/get.o", "br7dkxjkf1l14vq1mnlgpdc4x/add.o", "46eqkew3nhumtt4r2bpvdn0va/main.o"));

		Files.createFile(project.getProjectDir().toPath().resolve("build/obj/unrelated.o"));
		List<String> val2 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val2.add(it.getPath())));
		assertThat(val2, hasItem("unrelated.o"));

		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("native-task-object-files-extension");
		List<String> val3 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val2.add(it.getPath())));
		assertThat(val3, containsInAnyOrder("dtubmbufzoqugb7g2nr2ajmku/destructor.o", "bla582uw7ghia6h1j5306pigm/split.o", "5kossxetgzj7fykg9wq5u6rim/remove.o", "4xpvjqqo73v04ubildcqrv82l/copy_ctor_assign.o", "82e27d079y943zrk1pys1e2cn/size.o", "2f8iuoasryejxzyrn8m7wmkdv/join.o", "ev2fdlw45ba56yzx20i3maz1/message.o", "a3xkqa2f1a1r968dt6b5mdfl5/get.o", "br7dkxjkf1l14vq1mnlgpdc4x/add.o", "46eqkew3nhumtt4r2bpvdn0va/main.o"));
	}
}
