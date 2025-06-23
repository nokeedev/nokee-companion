package dev.nokee.companion.features;

import dev.gradleplugins.runnerkit.GradleExecutor;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.nokee.commons.gradle.file.SourceFileVisitor;
import dev.nokee.commons.sources.GradleBuildElement;
import dev.nokee.companion.NativeCompanionExtension;
import dev.nokee.companion.ObjectFiles;
import dev.nokee.elements.core.GradleLayoutElement;
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
import static dev.nokee.elements.core.ProjectElement.ofMain;
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
		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(build.getLocation());
		GradleRunner.create(GradleExecutor.gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().withTasks("compileReleaseCpp").build();

		FileUtils.copyDirectory(build.dir("build/obj/main/release").toFile(), project.getLayout().getBuildDirectory().dir("obj").get().getAsFile());

		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(project.getProjectDir().toPath());
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
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val3.add(it.getPath())));
		assertThat(val3, containsInAnyOrder("60yemoco4lam131yntpgpbh2r/size.o", "5v08dtn7cszvzrlgdayn1y9u6/main.o", "f0c36b8y9kkzr0hshipmuavim/message.o", "d7rpxlv4rhfra2lzuvpri9lyj/get.o", "c2kofpfbdvl0ox5prbhyuwx8n/join.o", "7l1rrxf4ugu1brfnb9msa2y3i/remove.o", "8dasnq975rzjosysh2j5fktog/split.o", "9ryzqhmpa69of9utjiizgddi3/copy_ctor_assign.o", "57uiwkglely1v0krq64yn8ivd/add.o", "3gfmv4hei6kdj2kxpjtavx6sh/destructor.o"));
	}

	@Test
	void assembly() throws IOException {
		System.out.println("Test directory: " + project.getProjectDir());
		GradleBuildElement build = GradleBuildElement.inDirectory(Files.createTempDirectory("gradle"));
		build.getBuildFile().plugins(it -> it.id("cpp-application"));
		build.getBuildFile().append(groovyDsl("tasks.withType(CppCompile).configureEach { source(fileTree('src/main/asm')) }"));
		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(build.getLocation());
		Files.list(build.getLocation().resolve("src/main/cpp")).forEach(it -> {
			try {
				Files.move(it, Files.createDirectories(new File(it.getParent().toString().replace("/src/main/cpp", "/src/main/asm")).toPath()).resolve(it.getFileName().toString().replace(".cpp", ".s")));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		GradleRunner.create(GradleExecutor.gradleTestKit()).inDirectory(build.getLocation()).withPluginClasspath().withTasks("compileReleaseCpp").build();

		FileUtils.copyDirectory(build.dir("build/obj/main/release").toFile(), project.getLayout().getBuildDirectory().dir("obj").get().getAsFile());

		new GradleLayoutElement().applyTo(ofMain(new CppApp())).writeToDirectory(project.getProjectDir().toPath());
		Files.list(project.getProjectDir().toPath().resolve("src/main/cpp")).forEach(it -> {
			try {
				Files.move(it, Files.createDirectories(new File(it.getParent().toString().replace("/src/main/cpp", "/src/main/asm")).toPath()).resolve(it.getFileName().toString().replace(".cpp", ".s")));
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

		assertThat(val, containsInAnyOrder("8wvym8e5j1aof35p5oepwi693/destructor.o", "5l7pivo7ae4yeh77kxo5h8e9c/split.o", "4u144ap9bqsoi5402yqiv0iww/remove.o", "6hvbcgbbb9cve2pvrw5gxqvb6/copy_ctor_assign.o", "d7uhq74mjmq4g00izk6nzdc9p/size.o", "3v2toruijtk75zj2gzow5b8qs/join.o", "7fwgkvuk3y0uxxwxv1xl2xb2e/message.o", "btaz7612dg8cg50cgcapecnnd/get.o", "37sxm9n411xbe76mb3c2ph9le/add.o", "b672h9phuoy3y80lzl59zlxqi/main.o"));

		Files.createFile(project.getProjectDir().toPath().resolve("build/obj/unrelated.o"));
		List<String> val2 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val2.add(it.getPath())));
		assertThat(val2, hasItem("unrelated.o"));

		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("native-task-object-files-extension");
		List<String> val3 = new ArrayList<>();
		project.files(ObjectFiles.of(compileTask)).getAsFileTree().visit(new SourceFileVisitor(it -> val3.add(it.getPath())));
		assertThat(val3, containsInAnyOrder("8wvym8e5j1aof35p5oepwi693/destructor.o", "5l7pivo7ae4yeh77kxo5h8e9c/split.o", "4u144ap9bqsoi5402yqiv0iww/remove.o", "6hvbcgbbb9cve2pvrw5gxqvb6/copy_ctor_assign.o", "d7uhq74mjmq4g00izk6nzdc9p/size.o", "3v2toruijtk75zj2gzow5b8qs/join.o", "7fwgkvuk3y0uxxwxv1xl2xb2e/message.o", "btaz7612dg8cg50cgcapecnnd/get.o", "37sxm9n411xbe76mb3c2ph9le/add.o", "b672h9phuoy3y80lzl59zlxqi/main.o"));
	}
}
