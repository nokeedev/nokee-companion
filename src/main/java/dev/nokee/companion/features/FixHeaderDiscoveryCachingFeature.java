package dev.nokee.companion.features;

import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.internal.file.TaskFileVarFactory;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.language.nativeplatform.internal.incremental.CompilationStateCacheFactory;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.internal.incremental.sourceparser.CSourceParser;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

abstract class FixHeaderDiscoveryCachingFeature implements Plugin<Project> {
	private final TaskContainer tasks;

	@Inject
	public FixHeaderDiscoveryCachingFeature(TaskContainer tasks) {
		this.tasks = tasks;
	}

	//region Services required by IncrementalCompilerBuilder
	@Inject protected abstract BuildOperationRunner getBuildOperationRunner();
	@Inject protected abstract CompilationStateCacheFactory getCompilationStateCacheFactory();
	@Inject protected abstract CSourceParser getSourceParser();
	@Inject protected abstract Deleter getDeleter();
	@Inject protected abstract DirectoryFileTreeFactory getDirectoryFileTreeFactory();
	@Inject protected abstract FileSystemAccess getFileSystemAccess();
	@Inject protected abstract TaskFileVarFactory getFileVarFactory();
	//endregion

	@Override
	public void apply(Project project) {
		tasks.withType(CppCompile.class).configureEach(task -> {
			incrementalCompilerBuilderOf(task).set(new DefaultIncrementalCompilerBuilder(getBuildOperationRunner(), getCompilationStateCacheFactory(), getSourceParser(), getDeleter(), getDirectoryFileTreeFactory(), getFileSystemAccess(), getFileVarFactory()));
		});
	}

	private static Property<IncrementalCompilerBuilder> incrementalCompilerBuilderOf(CppCompile task) {
		try {
			final Method CppCompile__getIncrementalCompilerBuilderService = task.getClass().getMethod("getIncrementalCompilerBuilderService");

			@SuppressWarnings("unchecked")
			final Property<IncrementalCompilerBuilder> result = (Property<IncrementalCompilerBuilder>) CppCompile__getIncrementalCompilerBuilderService.invoke(task);
			return result;
		} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
}
