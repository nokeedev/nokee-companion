package dev.nokee.companion.features;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import static dev.nokee.companion.features.ReflectionUtils.*;

/*private*/ abstract /*final*/ class ImproveSourceOrderingForHeaderDependenciesFeature implements Plugin<Project> {
	private final ObjectFactory objects;
	private final TaskContainer tasks;

	@Inject
	public ImproveSourceOrderingForHeaderDependenciesFeature(ObjectFactory objects, TaskContainer tasks) {
		this.objects = objects;
		this.tasks = tasks;
	}

	@Override
	public void apply(Project project) {
		// On Gradle older than 8.11, replace the source with sorted source.
		//   https://github.com/gradle/gradle/commit/aef36eb542ed2862eaf34cd1adfd0f469c230122
		if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) < 0) {
			tasks.withType(AbstractNativeCompileTask.class).configureEach(new FixAction());
		}
	}

	private final class FixAction implements Action<AbstractNativeCompileTask> {
		@Override
		public void execute(AbstractNativeCompileTask task) {
			patchSourceFiles(incrementalCompiler(task));
		}

		// We have to reach to AbstractNativeSourceCompileTask#incrementalCompiler
		private IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler(AbstractNativeCompileTask self) {
			return readFieldValue(AbstractNativeCompileTask.class, "incrementalCompiler", self);
		}

		private void patchSourceFiles(IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler) {
			try {
				// access StateCollectingIncrementalCompiler#sourceFiles
				Field StateCollectingIncrementalCompiler_sourceFiles = getField(incrementalCompiler.getClass(), "sourceFiles");
				makeAccessible(StateCollectingIncrementalCompiler_sourceFiles);

				// get current value of StateCollectingIncrementalCompiler#sourceFiles
				FileCollection sourceFiles = (FileCollection) StateCollectingIncrementalCompiler_sourceFiles.get(incrementalCompiler);

				removeFinal(StateCollectingIncrementalCompiler_sourceFiles);

				// override StateCollectingIncrementalCompiler#sourceFiles
				StateCollectingIncrementalCompiler_sourceFiles.set(incrementalCompiler, objects.fileCollection().from((Callable<?>) () -> new TreeSet<>(sourceFiles.getFiles())).builtBy(sourceFiles));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
