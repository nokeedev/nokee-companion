package dev.nokee.companion.features;

import dev.nokee.commons.gradle.Plugins;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import org.codehaus.groovy.runtime.HandleMetaClass;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithInstallation;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;

import javax.inject.Inject;
import java.util.function.Function;

/*private*/ abstract /*final*/ class GroovyMetaClassDecoratorFeature implements Plugin<Project> {
	@Inject
	public GroovyMetaClassDecoratorFeature() {}

	private static <T extends Task, OBJ> Provider<T> extensions(Object binary, String name, Function<OBJ, Provider<T>> getter) {
		@SuppressWarnings("unchecked")
		Provider<T> result = (Provider<T>) ((ExtensionAware) binary).getExtensions().findByName(name);
		if (result == null) {
			result = getter.apply((OBJ) binary);
		}
		return result;
	}

	@Override
	public void apply(Project project) {
		Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
			project.getComponents().withType(CppBinary.class).configureEach(binary -> {
				HandleMetaClass metaClass = new HandleMetaClass(((GroovyObject) binary).getMetaClass());
				metaClass.setProperty("getCompileTask", new Closure(null) {
					private Object doCall() {
						return extensions(getDelegate(), "compileTask", CppBinary::getCompileTask);
					}
				});

				if (binary instanceof ComponentWithExecutable) {
					metaClass.setProperty("getLinkTask", new Closure(null) {
						private Object doCall() {
							return extensions(getDelegate(), "linkTask", ComponentWithExecutable::getLinkTask);
						}
					});
				} else if (binary instanceof ComponentWithSharedLibrary) {
					metaClass.setProperty("getLinkTask", new Closure(null) {
						private Object doCall() {
							return extensions(getDelegate(), "linkTask", ComponentWithSharedLibrary::getLinkTask);
						}
					});
				} else if (binary instanceof ComponentWithStaticLibrary) {
					metaClass.setProperty("getCreateTask", new Closure(null) {
						private Object doCall() {
							return extensions(getDelegate(), "createTask", ComponentWithStaticLibrary::getCreateTask);
						}
					});
				}

				if (binary instanceof ComponentWithInstallation) {
					metaClass.setProperty("installTask", new Closure(null) {
						private Object doCall() {
							return extensions(getDelegate(), "installTask", ComponentWithInstallation::getInstallTask);
						}
					});
				}
			});
		});
	}
}
