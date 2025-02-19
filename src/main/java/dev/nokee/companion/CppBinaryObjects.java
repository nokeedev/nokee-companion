package dev.nokee.companion;

import dev.nokee.commons.gradle.Plugins;
import dev.nokee.commons.names.CppNames;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.language.nativeplatform.ComponentWithExecutable;
import org.gradle.language.nativeplatform.ComponentWithObjectFiles;
import org.gradle.language.nativeplatform.ComponentWithSharedLibrary;
import org.gradle.language.nativeplatform.ComponentWithStaticLibrary;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;

import javax.inject.Inject;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static dev.nokee.commons.names.CppNames.compileTaskName;
import static dev.nokee.commons.names.CppNames.linkTaskName;

/**
 * Represents the shadow property for {@link CppBinary#getObjects()}.
 */
public final class CppBinaryObjects {
	/**
	 * Returns the shadow property of {@link CppBinary#getObjects()}.
	 *
	 * @param binary  the binary with the objects
	 * @return the property
	 */
	public static ShadowProperty<FileCollection> objectsOf(ComponentWithObjectFiles binary) {
		return new ShadowProperty<>(binary, "objects", binary::getObjects);
	}

	/*private*/ static abstract /*final*/ class Rule implements Plugin<Project> {
		private final ObjectFactory objects;
		private final TaskContainer tasks;

		@Inject
		public Rule(ObjectFactory objects, TaskContainer tasks) {
			this.objects = objects;
			this.tasks = tasks;
		}

		@Override
		public void apply(Project project) {
			Plugins.forProject(project).whenPluginApplied(CppBasePlugin.class, () -> {
				project.getComponents().withType(CppBinary.class).configureEach(binary -> {
					final FileCollection objs = objects.fileCollection().from((Callable<?>) () -> {
						final CompileTasks compileTasks = (CompileTasks) ((ExtensionAware) binary).getExtensions().findByName("compileTasks");
						if (compileTasks != null) {
							return compileTasks.getElements().map(transformEach(ObjectFiles::of));
						}
						return tasks.named(compileTaskName(binary), CppCompile.class).map(ObjectFiles::of);
					});
					objectsOf(binary).set(objs);

					// Rewire the objects to account for the shadow property
					if (binary instanceof ComponentWithExecutable) {
						tasks.named(linkTaskName(binary), LinkExecutable.class, task -> {
							task.getSource().setFrom(objs);
						});
					} else if (binary instanceof ComponentWithSharedLibrary) {
						tasks.named(linkTaskName(binary), LinkSharedLibrary.class, task -> {
							task.getSource().setFrom(objs);
						});
					} else if (binary instanceof ComponentWithStaticLibrary) {
						tasks.named(CppNames.createTaskName(binary), CreateStaticLibrary.class, task -> {
							((ConfigurableFileCollection) task.getSource()).setFrom(objs);
						});
					}

					if (binary instanceof CppTestExecutable) {
						// TODO: Test suite
						// ... and rewire the object
					}
				});
			});
		}

		// TODO: Use transformEach for nokee-commons
		private /*static*/ <OUT, IN> Transformer<Iterable<OUT>, ? super Iterable<? extends IN>> transformEach(Transformer<OUT, IN> mapper) {
			return it -> StreamSupport.stream(it.spliterator(), false).map(mapper::transform).collect(Collectors.toList());
		}
	}
}
