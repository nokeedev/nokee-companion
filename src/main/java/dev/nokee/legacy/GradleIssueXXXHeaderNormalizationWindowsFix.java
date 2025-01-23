package dev.nokee.legacy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskContainer;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

// Avoid <WinSock.h> vs <winsock.h> vs <Windows.h> vs <windows.h>
//   important on case insensitive file system
/*private*/ abstract /*final*/ class GradleIssueXXXHeaderNormalizationWindowsFix implements Plugin<Project> {
	private final TaskContainer tasks;
	private final ObjectFactory objects;

	@Inject
	public GradleIssueXXXHeaderNormalizationWindowsFix(TaskContainer tasks, ObjectFactory objects) {
		this.tasks = tasks;
		this.objects = objects;
	}

	@Override
	public void apply(Project project) {
		// TODO: Check if windows (or case insensitive system)
		tasks.withType(CppCompileTask.class).configureEach(task -> {
			final FileCollection headerDependencies = task.headerDependencies;
			task.headerDependencies = objects.fileCollection().builtBy(headerDependencies).from((Callable<?>) () -> {
				List<File> result = new ArrayList<>();
				for (File file : headerDependencies.getFiles()) {
					// TODO: Should probably catch the exception and use original File in-case
					result.add(file.getCanonicalFile());
				}
				return result;
			});
		});
	}
}
