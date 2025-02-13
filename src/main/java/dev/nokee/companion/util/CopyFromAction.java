package dev.nokee.companion.util;

import dev.nokee.commons.gradle.tasks.options.SourceOptionsAware;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.FileSystemLocationProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.VisualCpp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CopyFromAction<T extends AbstractNativeCompileTask> implements Action<T> {
	private final Provider<T> other;

	private CopyFromAction(Provider<T> other) {
		this.other = other;
	}

	@Override
	public void execute(T task) {
		if (task instanceof CppCompile) {
			((CppCompile) task).getOptions().getDebuggable().convention(other.flatMap(it -> ((CppCompile) it).getOptions().getDebuggable()));
			((CppCompile) task).getOptions().getOptimized().convention(other.flatMap(it -> ((CppCompile) it).getOptions().getOptimized()));
			((CppCompile) task).getOptions().getPositionIndependentCode().convention(other.flatMap(it -> ((CppCompile) it).getOptions().getPositionIndependentCode()));
			((CppCompile) task).getOptions().getCompilerArgumentProviders().set(other.flatMap(it -> ((CppCompile) it).getOptions().getCompilerArgumentProviders()));
			((CppCompile) task).getOptions().getPreprocessorOptions().getDefinedMacros().set(other.flatMap(it -> ((CppCompile) it).getOptions().getPreprocessorOptions().getDefinedMacros()));

			if (((CppCompile) task).getOptions() instanceof SourceOptionsAware.Options) {
				((SourceOptionsAware.Options<?>) ((CppCompile) task).getOptions()).getBuckets().set(other.flatMap(it -> ((SourceOptionsAware.Options<?>) ((CppCompile) it).getOptions()).getBuckets()));
			}
		} else {
			final ProviderFactory providers = task.getProject().getProviders();

			// Override those properties as late as possible
			//  Note that a user won't be able to modify those values, however, we should be disallowing changes anyway.
			task.dependsOn(setProperty(task::setDebuggable, other.flatMap(it -> providers.provider(it::isDebuggable))));
			task.dependsOn(setProperty(task::setOptimized, other.flatMap(it -> providers.provider(it::isOptimized))));
			task.dependsOn(setProperty(task::setPositionIndependentCode, other.flatMap(it -> providers.provider(it::isPositionIndependentCode))));

			// TODO: Should we set or addAll?
			// Add macros as flag because CppCompile#macros is not a Gradle property.
			task.getCompilerArgs().addAll(task.getToolChain().zip(other.flatMap(it -> providers.provider(it::getMacros)), CopyFromAction::toMacroFlags));
		}

		// TODO: Should we set or addAll?
		task.getCompilerArgs().set(other.flatMap(AbstractNativeCompileTask::getCompilerArgs).orElse(Collections.emptyList()));

		// TODO: Should we setFrom or from?
		task.getIncludes().setFrom(other.flatMap(elementsOf(AbstractNativeCompileTask::getSource)));

		// TODO: Should we setFrom or from?
		task.getSystemIncludes().setFrom(other.flatMap(elementsOf(AbstractNativeCompileTask::getSystemIncludes)));

		// TODO: Should we convention or value?
		task.getTargetPlatform().convention(other.flatMap(AbstractNativeCompileTask::getTargetPlatform));
		task.getToolChain().convention(other.flatMap(AbstractNativeCompileTask::getToolChain));
		task.getObjectFileDir().convention(other.flatMap(locationOnly(AbstractNativeCompileTask::getObjectFileDir)));
	}

	private static <T> Callable<?> setProperty(Consumer<? super T> setter, Provider<T> provider) {
		return () -> {
			setter.accept(provider.get());
			return Collections.emptyList();
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

	// TODO: Move to nokee-commons
	private static <T> Transformer<Provider<Set<FileSystemLocation>>, T> elementsOf(Transformer<FileCollection, T> mapper) {
		return it -> mapper.transform(it).getElements();
	}

	// TODO: Move to nokee-commons
	private static <T, OUT extends FileSystemLocation> Transformer<Provider<OUT>, T> locationOnly(Transformer<FileSystemLocationProperty<OUT>, T> mapper) {
		return it -> mapper.transform(it).getLocationOnly();
	}

	public static <T extends AbstractNativeCompileTask> Action<T> copyFrom(TaskProvider<T> otherTask) {
		return new CopyFromAction<>(otherTask);
	}
}
