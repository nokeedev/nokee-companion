package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithInstallation;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

/**
 * Represents binary task extensions to avoid realizing the compile, link, create and install tasks.
 */
public final class CppBinaryTaskExtensions {
	/**
	 * {@return a configurable provider to the compile task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("unchecked")
	public static TaskProvider<CppCompile> compileTask(CppBinary binary) {
		return (TaskProvider<CppCompile>) ((ExtensionAware) binary).getExtensions().getByName("compileTask");
	}

	/**
	 * {@return a configurable provider to the link task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<LinkExecutable> linkTask(ComponentWithExecutable binary) {
		return (TaskProvider<LinkExecutable>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
	}

	/**
	 * {@return a configurable provider to the install task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<InstallExecutable> installTask(ComponentWithInstallation binary) {
		return (TaskProvider<InstallExecutable>) ((ExtensionAware) binary).getExtensions().getByName("installTask");
	}

	/**
	 * {@return a configurable provider to the link task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<LinkSharedLibrary> linkTask(ComponentWithSharedLibrary binary) {
		return (TaskProvider<LinkSharedLibrary>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
	}

	/**
	 * {@return a configurable provider to the create task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<CreateStaticLibrary> createTask(ComponentWithStaticLibrary binary) {
		return (TaskProvider<CreateStaticLibrary>) ((ExtensionAware) binary).getExtensions().getByName("createTask");
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private final TaskContainer tasks;

		@Inject
		public Rule(TaskContainer tasks) {
			this.tasks = tasks;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppComponent.class, component -> {
					component.getBinaries().whenElementKnown(CppBinary.class, binary -> {
						final TaskProvider<CppCompile> compileTask = tasks.named(compileTaskName(binary), CppCompile.class);
						((ExtensionAware) binary).getExtensions().add(new TypeOf<TaskProvider<CppCompile>>() {}, "compileTask", compileTask);

						if (binary instanceof ComponentWithExecutable) {
							final TaskProvider<LinkExecutable> linkTask = tasks.named(linkTaskName(binary), LinkExecutable.class);
							((ExtensionAware) binary).getExtensions().add(new TypeOf<TaskProvider<LinkExecutable>>() {}, "linkTask", linkTask);
						} else if (binary instanceof ComponentWithSharedLibrary) {
							final TaskProvider<LinkSharedLibrary> linkTask = tasks.named(linkTaskName(binary), LinkSharedLibrary.class);
							((ExtensionAware) binary).getExtensions().add(new TypeOf<TaskProvider<LinkSharedLibrary>>() {}, "linkTask", linkTask);
						} else if (binary instanceof ComponentWithStaticLibrary) {
							final TaskProvider<CreateStaticLibrary> createTask = tasks.named(createTaskName(binary), CreateStaticLibrary.class);
							((ExtensionAware) binary).getExtensions().add(new TypeOf<TaskProvider<CreateStaticLibrary>>() {}, "createTask", createTask);
						}

						if (binary instanceof ComponentWithInstallation) {
							final TaskProvider<InstallExecutable> installTask = tasks.named(installTaskName(binary), InstallExecutable.class);
							((ExtensionAware) binary).getExtensions().add(new TypeOf<TaskProvider<InstallExecutable>>() {}, "installTask", installTask);
						}
					});
				});
			});
		}
	}
}
