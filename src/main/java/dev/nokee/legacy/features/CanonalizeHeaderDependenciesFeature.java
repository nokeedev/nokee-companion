package dev.nokee.legacy.features;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.properties.LifecycleAwareValue;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

// Avoid <WinSock.h> vs <winsock.h> vs <Windows.h> vs <windows.h>
//   important on case insensitive file system
/*private*/ abstract /*final*/ class CanonalizeHeaderDependenciesFeature implements Plugin<Project> {
	private static final boolean IS_FILE_SYSTEM_CASE_INSENSITIVE = !new File("a").equals(new File("A"));
	private final TaskContainer tasks;
	private final ObjectFactory objects;

	@Inject
	public CanonalizeHeaderDependenciesFeature(TaskContainer tasks, ObjectFactory objects) {
		this.tasks = tasks;
		this.objects = objects;
	}

	@Override
	public void apply(Project project) {
		if (IS_FILE_SYSTEM_CASE_INSENSITIVE) {
			tasks.withType(CppCompileTask.class).configureEach(task -> {
				final FileCollection headerDependencies = task.headerDependencies;
				task.headerDependencies = objects.fileCollection().builtBy(headerDependencies).from(new Callable<Object>() {
					private List<File> cachedFiles;

					@Override
					public Object call() throws Exception {
						if (cachedFiles == null) {
							((LifecycleAwareValue) headerDependencies).prepareValue();
							List<File> result = new ArrayList<>();
							for (File file : headerDependencies.getFiles()) {
								// TODO: Should probably catch the exception and use original File in-case
								result.add(file.getCanonicalFile());
							}
							cachedFiles = result;
						}
						return cachedFiles;
					}
				});

				task.doLast(new Action<Task>() {
					@Override
					public void execute(Task task) {
						((LifecycleAwareValue) headerDependencies).cleanupValue();
					}
				});
			});
		}
	}
}
