package dev.nokee.legacy;

import org.gradle.api.Project;
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
/*private*/ abstract /*final*/ class GradleIssueXXXHeaderNormalizationWindowsFix extends FeaturePreviews.Plugin {
	private static final boolean IS_FILE_SYSTEM_CASE_INSENSITIVE = !new File("a").equals(new File("A"));
	private final TaskContainer tasks;
	private final ObjectFactory objects;

	@Inject
	public GradleIssueXXXHeaderNormalizationWindowsFix(TaskContainer tasks, ObjectFactory objects) {
		super("fix-headers-dependencies-for-case-insensitive");
		this.tasks = tasks;
		this.objects = objects;
	}

	@Override
	protected void doApply(Project project) {
		if (IS_FILE_SYSTEM_CASE_INSENSITIVE) {
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
}
