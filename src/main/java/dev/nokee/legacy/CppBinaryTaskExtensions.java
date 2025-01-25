package dev.nokee.legacy;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import org.codehaus.groovy.runtime.HandleMetaClass;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.cpp.tasks.CppCompile;
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

public final class CppBinaryTaskExtensions {
	@SuppressWarnings("unchecked")
	public static TaskProvider<CppCompile> compileTask(CppBinary binary) {
		return (TaskProvider<CppCompile>) ((ExtensionAware) binary).getExtensions().getByName("compileTask");
	}

	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<LinkExecutable> linkTask(ComponentWithExecutable binary) {
		return (TaskProvider<LinkExecutable>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
	}

	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<InstallExecutable> installTask(ComponentWithInstallation binary) {
		return (TaskProvider<InstallExecutable>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
	}

	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<LinkSharedLibrary> linkTask(ComponentWithSharedLibrary binary) {
		return (TaskProvider<LinkSharedLibrary>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
	}

	@SuppressWarnings({"unchecked", "UnstableApiUsage"})
	public static TaskProvider<CreateStaticLibrary> createTask(ComponentWithStaticLibrary binary) {
		return (TaskProvider<CreateStaticLibrary>) ((ExtensionAware) binary).getExtensions().getByName("createTask");
	}

	/*private*/ static abstract /*final*/ class Rule extends FeaturePreviews.Plugin {
		private final TaskContainer tasks;

		@Inject
		public Rule(TaskContainer tasks) {
			super("binary-task-extensions");
			this.tasks = tasks;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		protected void doApply(Project project) {
			project.getPlugins().withType(CppBasePlugin.class, ignored(() -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final TaskProvider<CppCompile> compileTask = tasks.named(compileTaskName(binary), CppCompile.class);
					((ExtensionAware) binary).getExtensions().add("compileTask", compileTask);

					HandleMetaClass metaClass = new HandleMetaClass(((GroovyObject) binary).getMetaClass());
					metaClass.setProperty("getCompileTask", new Closure(null) {
						private TaskProvider<CppCompile> doCall() {
							return compileTask((CppBinary) getDelegate());
						}
					});

					if (binary instanceof ComponentWithExecutable) {
						final TaskProvider<LinkExecutable> linkTask = tasks.named(linkTaskName(binary), LinkExecutable.class);
						((ExtensionAware) binary).getExtensions().add("linkTask", linkTask);
						metaClass.setProperty("getLinkTask", new Closure(null) {
							private TaskProvider<LinkExecutable> doCall() {
								return linkTask((ComponentWithExecutable) getDelegate());
							}
						});
					} else if (binary instanceof ComponentWithSharedLibrary) {
						final TaskProvider<LinkSharedLibrary> linkTask = tasks.named(linkTaskName(binary), LinkSharedLibrary.class);
						((ExtensionAware) binary).getExtensions().add("linkTask", linkTask);
						metaClass.setProperty("getLinkTask", new Closure(null) {
							private TaskProvider<LinkSharedLibrary> doCall() {
								return linkTask((ComponentWithSharedLibrary) getDelegate());
							}
						});
					} else if (binary instanceof ComponentWithStaticLibrary) {
						final TaskProvider<CreateStaticLibrary> createTask = tasks.named(createTaskName(binary), CreateStaticLibrary.class);
						((ExtensionAware) binary).getExtensions().add("createTask", createTask);
						metaClass.setProperty("getCreateTask", new Closure(null) {
							private TaskProvider<CreateStaticLibrary> doCall() {
								return createTask((ComponentWithStaticLibrary) getDelegate());
							}
						});
					}

					if (binary instanceof ComponentWithInstallation) {
						final TaskProvider<InstallExecutable> installTask = tasks.named(installTaskName(binary), InstallExecutable.class);
						((ExtensionAware) binary).getExtensions().add("installTask", installTask);
						metaClass.setProperty("installTask", new Closure(null) {
							private TaskProvider<InstallExecutable> doCall() {
								return installTask((ComponentWithInstallation) getDelegate());
							}
						});
					}

					((GroovyObject) binary).setMetaClass(metaClass);
				});
			}));
		}

		private static <T> Action<T> ignored(Runnable runnable) {
			return __ -> runnable.run();
		}
	}
}
