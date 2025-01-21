package dev.nokee.legacy;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.language.nativeplatform.internal.incremental.IncrementalCompilerBuilder;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/*private*/ abstract /*final*/ class GradleIssue29744Fix implements Plugin<Project> {
	private final ObjectFactory objects;

	@Inject
	public GradleIssue29744Fix(ObjectFactory objects) {
		this.objects = objects;
	}

	@Override
	public void apply(Project project) {
		// On Gradle older than 8.11, replace the source with sorted source.
		//   https://github.com/gradle/gradle/commit/aef36eb542ed2862eaf34cd1adfd0f469c230122
		if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) < 0) {
			project.getTasks().withType(AbstractNativeCompileTask.class).configureEach(new FixAction());
		}
	}

	private final class FixAction implements Action<AbstractNativeCompileTask> {
		@Override
		public void execute(AbstractNativeCompileTask task) {
			patchSourceFiles(incrementalCompiler(task));
		}

		// We have to reach to AbstractNativeSourceCompileTask#incrementalCompiler
		private IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler(AbstractNativeCompileTask self) {
			try {
				Field AbstractNativeCompileTask__incrementalCompiler = AbstractNativeCompileTask.class.getDeclaredField("incrementalCompiler");
				AbstractNativeCompileTask__incrementalCompiler.setAccessible(true);
				return (IncrementalCompilerBuilder.IncrementalCompiler) AbstractNativeCompileTask__incrementalCompiler.get(this);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}

		private void patchSourceFiles(IncrementalCompilerBuilder.IncrementalCompiler incrementalCompiler) {
			try {
				// access StateCollectingIncrementalCompiler#sourceFiles
				Field StateCollectingIncrementalCompiler_sourceFiles = incrementalCompiler.getClass().getDeclaredField("sourceFiles");
				StateCollectingIncrementalCompiler_sourceFiles.setAccessible(true);

				// get current value of StateCollectingIncrementalCompiler#sourceFiles
				FileCollection sourceFiles = (FileCollection) StateCollectingIncrementalCompiler_sourceFiles.get(incrementalCompiler);

				// remove final on StateCollectingIncrementalCompiler#sourceFiles
				Field StateCollectingIncrementalCompiler_sourceFiles_modifiers = Field.class.getDeclaredField("modifiers");
				StateCollectingIncrementalCompiler_sourceFiles_modifiers.setAccessible(true);
				StateCollectingIncrementalCompiler_sourceFiles.setInt(StateCollectingIncrementalCompiler_sourceFiles, StateCollectingIncrementalCompiler_sourceFiles.getModifiers() & ~Modifier.FINAL);

				// override StateCollectingIncrementalCompiler#sourceFiles
				StateCollectingIncrementalCompiler_sourceFiles.set(incrementalCompiler, objects.fileCollection().from((Callable<?>) () -> new TreeSet<>(sourceFiles.getFiles())).builtBy(sourceFiles));
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
