package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import groovy.lang.Closure;
import groovy.lang.ExpandoMetaClass;
import groovy.lang.GroovyObject;
import groovy.lang.MetaMethod;
import org.codehaus.groovy.runtime.HandleMetaClass;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithInstallation;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import javax.inject.Inject;
import java.util.Optional;
import java.util.function.BiConsumer;

import static dev.nokee.commons.names.CppNames.*;

/*private*/ abstract /*final*/ class GroovyMetaClassDecoratorFeature implements Plugin<Project> {
	private final TaskContainer tasks;

	@Inject
	public GroovyMetaClassDecoratorFeature(TaskContainer tasks) {
		this.tasks = tasks;
	}

	private static final class MetaMethodClosure extends Closure {
		private final String method;

		public MetaMethodClosure(Object owner, MetaMethod method) {
			super(owner);
			this.method = method.getName();

			this.maximumNumberOfParameters = method.getParameterTypes().length;
			this.parameterTypes = method.getNativeParameterTypes();
		}

		public Object doCall(Object... arguments) {
			String method = this.method;
			return InvokerHelper.invokeMethod(getOwner(), method, arguments);
		}
	}

	private static <T extends Task> void decorate(TaskProvider<T> taskProvider, BiConsumer<? super TaskProvider<T>, ? super MetaMethod> action) {
		InvokerHelper.getMetaClass(taskProvider).respondsTo(taskProvider, "configure")
			.forEach(m -> action.accept(taskProvider, m));
	}

	private static <T extends Task> BiConsumer<TaskProvider<T>, MetaMethod> to(HandleMetaClass metaClass, String methodName) {
		return (taskProvider, m) -> metaClass.setProperty(methodName, new MetaMethodClosure(taskProvider, m));
	}

	private static HandleMetaClass metaClassOf(Object object) {
		return Optional.ofNullable(((ExtensionAware) object).getExtensions().findByName("$__nk_metaClass"))
			.map(HandleMetaClass.class::cast)
			.orElseGet(() -> {
				HandleMetaClass result = new HandleMetaClass(((GroovyObject) object).getMetaClass(), object);
				((ExtensionAware) object).getExtensions().add("$__nk_metaClass", result);
				((GroovyObject) object).setMetaClass(result);
				return result;
			});
	}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				final HandleMetaClass metaClass = metaClassOf(binary);

				decorate(tasks.named(compileTaskName(binary)), to(metaClass, "compileTask"));

				if (binary instanceof ComponentWithExecutable || binary instanceof ComponentWithSharedLibrary) {
					decorate(tasks.named(linkTaskName(binary)), to(metaClass, "linkTask"));
				} else if (binary instanceof ComponentWithStaticLibrary) {
					decorate(tasks.named(createTaskName(binary)), to(metaClass, "createTask"));
				}

				if (binary instanceof ComponentWithInstallation) {
					decorate(tasks.named(installTaskName(binary)), to(metaClass, "installTask"));
				}
			});
		});

		// Must wait for the plugin to be applied to ensure configuration ordering (run task gets created).
		Plugins.forProject(project).whenPluginApplied("cpp-unit-test", () -> {
			project.getComponents().withType(CppTestSuite.class).configureEach(component -> {
				component.getBinaries().whenElementKnown(CppTestExecutable.class, binary -> {
					decorate(tasks.named(installTaskName(binary)), to(metaClassOf(binary), "runTask"));
				});
			});
		});
	}
}
