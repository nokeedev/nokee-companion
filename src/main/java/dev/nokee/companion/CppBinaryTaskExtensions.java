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
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;

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

	/**
	 * {@return a configurable provider to the run task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("unchecked")
	public static TaskProvider<RunTestExecutable> runTask(CppTestExecutable binary) {
		return (TaskProvider<RunTestExecutable>) ((ExtensionAware) binary).getExtensions().getByName("runTask");
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private static final TypeOf<TaskProvider<CppCompile>> COMPILE_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<CppCompile>>() {};
		private static final TypeOf<TaskProvider<LinkExecutable>> LINK_EXECUTABLE_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<LinkExecutable>>() {};
		private static final TypeOf<TaskProvider<LinkSharedLibrary>> LINK_SHARED_LIBRARY_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<LinkSharedLibrary>>() {};
		private static final TypeOf<TaskProvider<CreateStaticLibrary>> CREATE_STATIC_LIBRARY_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<CreateStaticLibrary>>() {};
		private static final TypeOf<TaskProvider<InstallExecutable>> INSTALL_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<InstallExecutable>>() {};
		private static final TypeOf<TaskProvider<RunTestExecutable>> RUN_TASK_PROVIDER_TYPE = new TypeOf<TaskProvider<RunTestExecutable>>() {};
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
						((ExtensionAware) binary).getExtensions().add(COMPILE_TASK_PROVIDER_TYPE, "compileTask", compileTask);

						if (binary instanceof ComponentWithExecutable) {
							final TaskProvider<LinkExecutable> linkTask = tasks.named(linkTaskName(binary), LinkExecutable.class);
							((ExtensionAware) binary).getExtensions().add(LINK_EXECUTABLE_TASK_PROVIDER_TYPE, "linkTask", linkTask);
						} else if (binary instanceof ComponentWithSharedLibrary) {
							final TaskProvider<LinkSharedLibrary> linkTask = tasks.named(linkTaskName(binary), LinkSharedLibrary.class);
							((ExtensionAware) binary).getExtensions().add(LINK_SHARED_LIBRARY_TASK_PROVIDER_TYPE, "linkTask", linkTask);
						} else if (binary instanceof ComponentWithStaticLibrary) {
							final TaskProvider<CreateStaticLibrary> createTask = tasks.named(createTaskName(binary), CreateStaticLibrary.class);
							((ExtensionAware) binary).getExtensions().add(CREATE_STATIC_LIBRARY_TASK_PROVIDER_TYPE, "createTask", createTask);
						}

						if (binary instanceof ComponentWithInstallation) {
							final TaskProvider<InstallExecutable> installTask = tasks.named(installTaskName(binary), InstallExecutable.class);
							((ExtensionAware) binary).getExtensions().add(INSTALL_TASK_PROVIDER_TYPE, "installTask", installTask);
						}
					});
				});
			});

			Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
				project.getComponents().withType(CppTestSuite.class).configureEach(component -> {
					component.getBinaries().whenElementKnown(CppTestExecutable.class, binary -> {
						final TaskProvider<RunTestExecutable> runTask = tasks.named(runTaskName(binary), RunTestExecutable.class);
						((ExtensionAware) binary).getExtensions().add(RUN_TASK_PROVIDER_TYPE, "runTask", runTask);
					});
				});
			});
		}
	}
}
