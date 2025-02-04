package dev.nokee.companion;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import org.codehaus.groovy.runtime.HandleMetaClass;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
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
import java.util.function.Function;

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
		return (TaskProvider<InstallExecutable>) ((ExtensionAware) binary).getExtensions().getByName("linkTask");
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

	private static <T extends Task, OBJ> Provider<T> extensions(Object binary, String name, Function<OBJ, Provider<T>> getter) {
		Provider<T> result = (Provider<T>) ((ExtensionAware) binary).getExtensions().findByName(name);
		if (result == null) {
			result = getter.apply((OBJ) binary);
		}
		return result;
	}

	/*private*/ static abstract /*final*/ class Feature implements Plugin<Project> {
		private final TaskContainer tasks;

		@Inject
		public Feature(TaskContainer tasks) {
			this.tasks = tasks;
		}

		@Override
		@SuppressWarnings("UnstableApiUsage")
		public void apply(Project project) {
			project.getPlugins().withType(CppBasePlugin.class, ignored(() -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final TaskProvider<CppCompile> compileTask = tasks.named(compileTaskName(binary), CppCompile.class);
					((ExtensionAware) binary).getExtensions().add("compileTask", compileTask);

					HandleMetaClass metaClass = new HandleMetaClass(((GroovyObject) binary).getMetaClass());
					metaClass.setProperty("getCompileTask", new Closure(null) {
						private Object doCall() {
							return extensions(getDelegate(), "compileTask", CppBinary::getCompileTask);
						}
					});

					if (binary instanceof ComponentWithExecutable) {
						final TaskProvider<LinkExecutable> linkTask = tasks.named(linkTaskName(binary), LinkExecutable.class);
						((ExtensionAware) binary).getExtensions().add("linkTask", linkTask);
						metaClass.setProperty("getLinkTask", new Closure(null) {
							private Object doCall() {
								return extensions(getDelegate(), "linkTask", ComponentWithExecutable::getLinkTask);
							}
						});
					} else if (binary instanceof ComponentWithSharedLibrary) {
						final TaskProvider<LinkSharedLibrary> linkTask = tasks.named(linkTaskName(binary), LinkSharedLibrary.class);
						((ExtensionAware) binary).getExtensions().add("linkTask", linkTask);
						metaClass.setProperty("getLinkTask", new Closure(null) {
							private Object doCall() {
								return extensions(getDelegate(), "linkTask", ComponentWithSharedLibrary::getLinkTask);
							}
						});
					} else if (binary instanceof ComponentWithStaticLibrary) {
						final TaskProvider<CreateStaticLibrary> createTask = tasks.named(createTaskName(binary), CreateStaticLibrary.class);
						((ExtensionAware) binary).getExtensions().add("createTask", createTask);
						metaClass.setProperty("getCreateTask", new Closure(null) {
							private Object doCall() {
								return extensions(getDelegate(), "createTask", ComponentWithStaticLibrary::getCreateTask);
							}
						});
					}

					if (binary instanceof ComponentWithInstallation) {
						final TaskProvider<InstallExecutable> installTask = tasks.named(installTaskName(binary), InstallExecutable.class);
						((ExtensionAware) binary).getExtensions().add("installTask", installTask);
						metaClass.setProperty("installTask", new Closure(null) {
							private Object doCall() {
								return extensions(getDelegate(), "installTask", ComponentWithInstallation::getInstallTask);
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
