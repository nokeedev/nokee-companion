package dev.nokee.companion.util;

import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.toolchain.GccCompatibleToolChain;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class AvoidOverlinkingAction implements Action<AbstractLinkTask> {
	private final FileCollection linkLibraries;
	private final FileCollection runtimeLibraries;
	private final ProjectLayout layout;

	private AvoidOverlinkingAction(FileCollection linkLibraries, FileCollection runtimeLibraries, ProjectLayout layout) {
		this.linkLibraries = linkLibraries;
		this.runtimeLibraries = runtimeLibraries;
		this.layout = layout;
	}

	@Override
	public void execute(AbstractLinkTask task) {
		if (!task.getProject().getPlugins().hasPlugin("native-companion.features.overlinking-avoidance")) {
			return; // skip configuration as overlinking avoidance is disabled
		}

		class Call implements Callable<Object> {
			Call() {
				task.getExtensions().getExtraProperties().set("overlinkingLinkerArgsEnabled", true);
			}

			@Override
			public Object call() throws Exception {
				Object value = task.getExtensions().getExtraProperties().get("overlinkingLinkerArgsEnabled");
				if ((value instanceof Boolean) && (Boolean) value) {
					return new Object();
				}
				task.getLogger().info(String.format("Overlinking avoidance linker args from %s are not consider.", task));
				return null;
			}
		}
		Provider<Object> overlinkingLinkerArgsEnabled = task.getProject().provider(new Call());

		Provider<Set<FileSystemLocation>> additionalDependencies = overlinkingLinkerArgsEnabled.flatMap(constant(rPathLinkSupported(task).flatMap(constant(secondLevelDependencies()))))
			.orElse(Collections.emptySet());

		task.getInputs().files(additionalDependencies);
		task.getLinkerArgs().addAll(additionalDependencies.map(asRPathLinkArgs()));
	}

	private Provider<Set<FileSystemLocation>> secondLevelDependencies() {
		return runtimeLibraries.minus(linkLibraries).getElements();
	}

	private Provider<Object> rPathLinkSupported(AbstractLinkTask task) {
		return task.getToolChain().zip(task.getTargetPlatform(), (toolchain, platform) -> (toolchain instanceof GccCompatibleToolChain && platform.getOperatingSystem().isLinux()) ? new Object() : null);
	}

	private Transformer<List<String>, Set<FileSystemLocation>> asRPathLinkArgs() {
		return libraries -> {
			// Use relative path to avoid trashing the up-to-date checking and cacheability.
			return libraries.stream().map(it -> "-Wl,-rpath-link=" + layout.getProjectDirectory().getAsFile().toPath().relativize(it.getAsFile().getParentFile().toPath())).collect(Collectors.toList());
		};
	}

	private static <T> Transformer<T, Object> constant(T value) {
		return __ -> value;
	}

	public static Action<AbstractLinkTask> avoidOverlinking(Provider<Configuration> nativeLink, Provider<Configuration> nativeRuntime) {
		return task -> {
			avoidOverlinking(task.getProject().files((Callable<?>) nativeLink::get), task.getProject().files((Callable<?>) nativeRuntime::get)).execute(task);
		};
	}

	public static Action<AbstractLinkTask> avoidOverlinking(FileCollection linkLibraries, FileCollection runtimeLibraries) {
		return task -> {
			new AvoidOverlinkingAction(linkLibraries, runtimeLibraries, task.getProject().getLayout()).execute(task);
		};
	}
}
