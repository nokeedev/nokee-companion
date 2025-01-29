package dev.nokee.legacy;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingScheme;
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingSchemeFactory;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

final class ObjectFiles {
	// Conceptually, objectFiles are either an extensions or derived from AbstractNativeCompileTask#getObjectFileDir
	//  or we could consider any FileCollection getObjects()/getObjectFiles()
	public static Object of(Object self) {
		FileTree result = null;
		if (self instanceof ExtensionAware) {
			result = (FileTree) ((ExtensionAware) self).getExtensions().findByName("objectFiles");
		}

		if (result == null && self instanceof AbstractNativeCompileTask) {
			result = ((AbstractNativeCompileTask) self).getObjectFileDir().getAsFileTree().matching(it -> it.include("**/*.o", "**/*.obj"));
		}

		if (result == null) {
			return Collections.emptyList();
		}

		return result;
	}

	/*private*/ static abstract /*final*/ class Feature implements Plugin<Project> {
		@Inject
		public Feature() {}

		@Override
		public void apply(Project project) {
			project.getTasks().withType(AbstractNativeCompileTask.class).configureEach(new Action<>() {
				private /*static*/ String objectFileExtension(AbstractNativeCompileTask task) {
					NativeToolChainInternal nativeToolChain = (NativeToolChainInternal) task.getToolChain().get();
					NativePlatformInternal nativePlatform = (NativePlatformInternal) task.getTargetPlatform().get();
					PlatformToolProvider platformToolProvider = nativeToolChain.select(nativePlatform);
					return platformToolProvider.getObjectFileExtension();
				}

				@Override
				public void execute(AbstractNativeCompileTask task) {
					final FileTree objectFiles = project.getObjects().fileCollection().builtBy(task).from((Callable<?>) () -> {
						final File objectFileDir = task.getObjectFileDir().getLocationOnly().get().getAsFile();
						final List<Object> result = new ArrayList<>();
						final CompilerOutputFileNamingScheme outputFileNamingScheme = ((ProjectInternal) project).getServices().get(CompilerOutputFileNamingSchemeFactory.class).create()
							.withOutputBaseFolder(objectFileDir).withObjectFileNameSuffix(objectFileExtension(task));

						// Map all source files to object files
						for (File sourceFile : task.getSource()) {
							result.add(outputFileNamingScheme.map(sourceFile));
						}
						return result;
					}).getAsFileTree(); // Use FileTree to remove missing object files

					// Mount as extension
					task.getExtensions().add("objectFiles", objectFiles);
				}
			});
		}
	}
}
