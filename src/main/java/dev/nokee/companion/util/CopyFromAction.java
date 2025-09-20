package dev.nokee.companion.util;

import dev.nokee.commons.gradle.tasks.options.SourceOptions;
import dev.nokee.commons.gradle.tasks.options.SourceOptionsAware;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.VisualCpp;
import org.gradle.process.CommandLineArgumentProvider;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static dev.nokee.commons.gradle.provider.ProviderUtils.elementsOf;
import static java.util.Collections.emptyList;

/**
 * Utility action that copies the configuration from a task to another.
 *
 * @param <T>  must be {@link AbstractNativeCompileTask}, {@link AbstractLinkTask} or {@link CppCompile}
 */
public class CopyFromAction<T extends Task> implements Action<T> {
	private final Provider<? extends T> other;

	private CopyFromAction(Provider<? extends T> other) {
		this.other = other;
	}

	@Override
	public void execute(T task) {
		if (task instanceof AbstractNativeCompileTask) {
			doExecute((AbstractNativeCompileTask) task, other.map(AbstractNativeCompileTask.class::cast));
		} else if (task instanceof AbstractLinkTask) {
			doExecute((AbstractLinkTask) task, other.map(AbstractLinkTask.class::cast));
		} else {
			throw new UnsupportedOperationException("does not support copying specified task type");
		}
	}

	private void doExecute(AbstractLinkTask task, Provider<? extends AbstractLinkTask> other) {
		task.getDebuggable().set((Boolean) null); // reset default value

		task.getDebuggable().convention(other.flatMap(AbstractLinkTask::getDebuggable));
		task.getToolChain().convention(other.flatMap(AbstractLinkTask::getToolChain));
		task.getTargetPlatform().convention(other.flatMap(AbstractLinkTask::getTargetPlatform));
		task.getLibs().from(other.flatMap(elementsOf(AbstractLinkTask::getLibs)));

		// WARNING: User must configure overlinking avoidance on the copied task using {@code newLinkTask.configure(avoidOverlinking(nativeLink, nativeRuntime))}.
		task.getLinkerArgs().addAll(other.flatMap(withoutOverlinkingAvoidanceArgs(AbstractLinkTask::getLinkerArgs)));

		task.getSource().from(other.flatMap(elementsOf(AbstractLinkTask::getSource)));

		// WARNING: User must set AbstractLinkTask#getLinkedFile()
		//   Internally, the task infers AbstractLinkTask#getDestinationDirectory()

		if (task instanceof LinkSharedLibrary) {
			((LinkSharedLibrary) task).getInstallName().convention(other.flatMap(this::toInstallName));
			// WARNING: User must set LinkSharedLibrary#getImportLibrary()
		}
	}

	private static Transformer<Provider<List<String>>, AbstractLinkTask> withoutOverlinkingAvoidanceArgs(Transformer<Provider<List<String>>, AbstractLinkTask> mapper) {
		return task -> {
			ListProperty<String> prop = task.getProject().getObjects().listProperty(String.class);
			prop.addAll(task.getProject().provider(() -> {
				task.getExtensions().getExtraProperties().set("overlinkingLinkerArgsEnabled", false);
				return Collections.<String>emptyList();
			}).flatMap(it -> task.getProject().provider(() -> it)));
			prop.addAll(task.getLinkerArgs());
			prop.addAll(task.getProject().provider(() -> {
				task.getExtensions().getExtraProperties().set("overlinkingLinkerArgsEnabled", true);
				return Collections.<String>emptyList();
			}).flatMap(it -> task.getProject().provider(() -> it)));
			return prop;
		};
	}

	private @Nullable Provider<String> toInstallName(AbstractLinkTask task) {
		if (task instanceof LinkSharedLibrary) {
			return ((LinkSharedLibrary) task).getInstallName();
		} else {
			return null;
		}
	}

	private void doExecute(AbstractNativeCompileTask task, Provider<? extends AbstractNativeCompileTask> other) {
		if (task instanceof CppCompile) {
			((CppCompile) task).getOptions().getDebuggable().convention(other.flatMap(this::toDebuggable));
			((CppCompile) task).getOptions().getOptimized().convention(other.flatMap(this::toOptimized));
			((CppCompile) task).getOptions().getPositionIndependentCode().convention(other.flatMap(this::toPositionIndependentCode));
			((CppCompile) task).getOptions().getCompilerArgumentProviders().addAll(other.flatMap(this::toCompilerArgumentProviders).orElse(emptyList()));
			((CppCompile) task).getOptions().getPreprocessorOptions().defines(other.flatMap(this::toDefinedMacros));
			((CppCompile) task).getOptions().getIncrementalAfterFailure().convention(other.flatMap(this::toIncrementalAfterFailure));

			if (task instanceof SourceOptionsAware) {
				((SourceOptionsAware) task).getSourceOptions().with(other.flatMap(this::toSourceOptions));
			}
		} else {
			final ProviderFactory providers = task.getProject().getProviders();

			// Override those properties as late as possible
			//  Note that a user won't be able to modify those values, however, we should be disallowing changes anyway.
			task.dependsOn(setProperty(task::setDebuggable, other.flatMap(this::toDebuggable)));
			task.dependsOn(setProperty(task::setOptimized, other.flatMap(this::toOptimized)));
			task.dependsOn(setProperty(task::setPositionIndependentCode, other.flatMap(this::toPositionIndependentCode)));

			// Add macros as flag because CppCompile#macros is not a Gradle property.
			task.getCompilerArgs().addAll(task.getToolChain().zip(other.flatMap(it -> providers.provider(it::getMacros)), CopyFromAction::toMacroFlags));
		}

		task.getCompilerArgs().addAll(other.flatMap(AbstractNativeCompileTask::getCompilerArgs).orElse(emptyList()));

		task.getSource().from(other.flatMap(elementsOf(AbstractNativeCompileTask::getSource)));
		task.getIncludes().from(other.flatMap(elementsOf(AbstractNativeCompileTask::getIncludes)));

		task.getSystemIncludes().from(other.flatMap(elementsOf(AbstractNativeCompileTask::getSystemIncludes)));

		task.getTargetPlatform().convention(other.flatMap(AbstractNativeCompileTask::getTargetPlatform));
		task.getToolChain().convention(other.flatMap(AbstractNativeCompileTask::getToolChain));
		// WARNING: Users must set AbstractNativeCompileTask#ObjectFileDir()
	}

	private Provider<Boolean> toDebuggable(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getDebuggable();
		} else {
			return task.getProject().provider(task::isDebuggable);
		}
	}

	private Provider<Boolean> toOptimized(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getOptimized();
		} else {
			return task.getProject().provider(task::isOptimized);
		}
	}

	private Provider<Boolean> toPositionIndependentCode(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getPositionIndependentCode();
		} else {
			return task.getProject().provider(task::isPositionIndependentCode);
		}
	}

	private Provider<? extends Object> toDefinedMacros(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getPreprocessorOptions().getDefinedMacros();
		} else {
			return task.getProject().provider(task::getMacros);
		}
	}

	private @Nullable Provider<List<CommandLineArgumentProvider>> toCompilerArgumentProviders(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getCompilerArgumentProviders();
		} else {
			return null; // nothing to map
		}
	}

	private @Nullable Provider<Boolean> toIncrementalAfterFailure(AbstractNativeCompileTask task) {
		if (task instanceof CppCompile) {
			return ((CppCompile) task).getOptions().getIncrementalAfterFailure();
		} else {
			return null;
		}
	}

	private @Nullable Provider<SourceOptions> toSourceOptions(AbstractNativeCompileTask task) {
		if (task instanceof SourceOptionsAware) {
			return task.getProject().provider(() -> ((SourceOptionsAware<?>) task).getSourceOptions().forFiles(task.getSource().getAsFileTree()));
		} else {
			return null;
		}
	}

	private static <T> Callable<?> setProperty(Consumer<? super T> setter, Provider<T> provider) {
		return () -> {
			setter.accept(provider.get());
			return emptyList();
		};
	}

	private static List<String> toMacroFlags(NativeToolChain toolChain, Map<String, String> macros) {
		return macros.entrySet().stream().map(it -> {
			final StringBuilder builder = new StringBuilder();

			if (toolChain instanceof VisualCpp)
				builder.append("/D");
			else
				builder.append("-D");

			builder.append(it.getKey());
			if (it.getValue() != null) {
				builder.append("=").append(it.getValue());
			}
			return builder.toString();
		}).collect(Collectors.toList());
	}

	/**
	 * Copies the configuration of the specified task to the current task.
	 * <p>
	 * We only copy the configuration that are safe to duplicate.
	 *
	 * @param otherTask  the task to copy the configuration from
	 * @return a configuration action that will perform the copy
	 * @param <T>  must be {@link AbstractNativeCompileTask}, {@link AbstractLinkTask} or {@link CppCompile}
	 */
	public static <T extends Task> Action<T> copyFrom(Provider<? extends T> otherTask) {
		return new CopyFromAction<>(otherTask);
	}
}
