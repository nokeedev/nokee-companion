package dev.nokee.companion.features;

import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

abstract class FixHeaderDiscoveryCachingFeature implements Plugin<Project> {
	@Inject
	public FixHeaderDiscoveryCachingFeature() {}

	@Inject protected abstract TaskContainer getTasks();

	@Override
	public void apply(Project project) {
		getTasks().withType(CppCompile.class).configureEach(task -> {
			incrementalCompilerBuilderOf(task).set(DefaultIncrementalCompilerBuilder.class);
		});
	}

	private static Property<Class<? extends IncrementalCompilerBuilder>> incrementalCompilerBuilderOf(CppCompile task) {
		try {
			final Method CppCompile__getIncrementalCompilerBuilderService = task.getClass().getMethod("getIncrementalCompilerBuilderClass");

			@SuppressWarnings("unchecked")
			final Property<Class<? extends IncrementalCompilerBuilder>> result = (Property<Class<? extends IncrementalCompilerBuilder>>) CppCompile__getIncrementalCompilerBuilderService.invoke(task);
			return result;
		} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
}
