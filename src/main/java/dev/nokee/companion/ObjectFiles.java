package dev.nokee.companion;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.language.assembler.tasks.Assemble;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingScheme;
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingSchemeFactory;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;

import javax.inject.Inject;
import java.io.File;
import java.util.concurrent.Callable;

/**
 * Represents object files of a target.
 */
public final class ObjectFiles {
	// Conceptually, objectFiles are either an extensions or derived from AbstractNativeCompileTask#getObjectFileDir/Assemble#getObjectFileDir
	//  or we could consider any FileCollection getObjects()/getObjectFiles()

	/**
	 * Returns the object files of the specified object.
	 * Supported object are:
	 * <ul>
	 *     <li><code>objectFiles</code> extension</li>
	 *     <li>{@link AbstractNativeCompileTask#getObjectFileDir()} matching {@literal .o} and {@literal .obj} files</li>
	 * </ul>
	 *
	 * @param self  the object to retrieve the object files
	 * @return the object files
	 */
	public static Object of(Object self) {
		FileTree result = null;
		if (self instanceof ExtensionAware) {
			result = (FileTree) ((ExtensionAware) self).getExtensions().findByName("objectFiles");
		}

		if (result == null) {
			if (self instanceof AbstractNativeCompileTask) {
				result = ((AbstractNativeCompileTask) self).getObjectFileDir().getAsFileTree().matching(objectFiles());
			} else if (self instanceof Assemble) {
				result = ((Task) self).getProject().fileTree(((Assemble) self).getObjectFileDir()).matching(objectFiles());
			}
		}

		if (result == null) {
			// TODO: Should we throw an exception or just warn
			throw new IllegalArgumentException("object files cannot be retrieved from " + self);
		}

		return result;
	}

	private static Action<PatternFilterable> objectFiles() {
		return it -> it.include("**/*.o", "**/*.obj");
	}

	/*private*/ static abstract /*final*/ class Feature implements Plugin<Project> {
		@Inject
		public Feature() {}

		@Override
		public void apply(Project project) {
			project.getTasks().withType(AbstractNativeCompileTask.class).configureEach(new ConfigureObjectFilesExtension<AbstractNativeCompileTask>() {
				@Override
				protected NativeToolChainInternal toolChainOf(AbstractNativeCompileTask task) {
					return (NativeToolChainInternal) task.getToolChain().get();
				}

				@Override
				protected NativePlatformInternal targetPlatformOf(AbstractNativeCompileTask task) {
					return (NativePlatformInternal) task.getTargetPlatform().get();
				}

				@Override
				protected Iterable<File> sourceOf(AbstractNativeCompileTask task) {
					return task.getSource();
				}

				@Override
				protected Callable<File> objectFileDirOf(AbstractNativeCompileTask task) {
					return task.getObjectFileDir().getLocationOnly().map(Directory::getAsFile)::get;
				}
			});
			project.getTasks().withType(Assemble.class).configureEach(new ConfigureObjectFilesExtension<Assemble>() {
				@Override
				protected NativeToolChainInternal toolChainOf(Assemble task) {
					return (NativeToolChainInternal) task.getToolChain().get();
				}

				@Override
				protected NativePlatformInternal targetPlatformOf(Assemble task) {
					return (NativePlatformInternal) task.getTargetPlatform().get();
				}

				@Override
				protected Iterable<File> sourceOf(Assemble task) {
					return task.getSource();
				}

				@Override
				protected Callable<File> objectFileDirOf(Assemble task) {
					return () -> task.getObjectFileDir();
				}
			});
		}

		private static abstract class ConfigureObjectFilesExtension<T extends Task> implements Action<T> {
			protected abstract NativeToolChainInternal toolChainOf(T task);
			protected abstract NativePlatformInternal targetPlatformOf(T task);
			protected abstract Iterable<File> sourceOf(T task);
			protected abstract Callable<File> objectFileDirOf(T task);

			private String objectFileExtension(T task) {
				NativeToolChainInternal nativeToolChain = toolChainOf(task);
				NativePlatformInternal nativePlatform = targetPlatformOf(task);
				PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);
				return platformToolProvider.getObjectFileExtension();
			}

			@Override
			public final void execute(T task) {
				final FileTree objectFiles = task.getProject().getObjects().fileTree().setDir(objectFileDirOf(task)).builtBy(task).matching(it -> {
					final File objectFileDir;
					try {
						objectFileDir = objectFileDirOf(task).call();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
					final CompilerOutputFileNamingScheme outputFileNamingScheme = ((ProjectInternal) task.getProject()).getServices().get(CompilerOutputFileNamingSchemeFactory.class).create()
						.withOutputBaseFolder(objectFileDir).withObjectFileNameSuffix(objectFileExtension(task));

					// Map all source files to object files
					for (File sourceFile : sourceOf(task)) {
						System.out.println("- " + outputFileNamingScheme.map(sourceFile));
						it.include(objectFileDir.toPath().relativize(outputFileNamingScheme.map(sourceFile).toPath()).toString());
					}
				}).getAsFileTree(); // Use FileTree to remove missing object files

				// Mount as extension
				task.getExtensions().add("objectFiles", objectFiles);
			}
		}
	}
}
