package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.internal.WorkCompletionNotifier;
import dev.nokee.nativeplatform.tasks.internal.WorkListenerSupport;
import dev.nokee.nativeplatform.tasks.internal.WorkPlanSupport;
import dev.nokee.work.WorkCompletionListener;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.Cast;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.internal.operations.logging.BuildOperationLoggerFactory;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.tasks.StaleOutputCleaner;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.internal.LinkerSpec;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;

import javax.annotation.Nullable;

final class LinkSupport {
	private LinkSupport() {}

	static void link(AbstractLinkTask task, Deleter deleter, BuildOperationLoggerFactory operationLoggerFactory, LinkerSpec spec, WorkCompletionListener listener) {
		boolean cleanedOutputs = StaleOutputCleaner.cleanOutputs(deleter, task.getOutputs().getPreviousOutputFiles(), task.getDestinationDirectory().get().getAsFile());

		if (task.getSource().isEmpty()) {
			task.setDidWork(cleanedOutputs);
			return;
		}

		populate(task, spec, operationLoggerFactory.newOperationLogger(task.getName(), task.getTemporaryDir()));

		Compiler<LinkerSpec> linker = newLinker(task, spec);
		WorkListenerSupport.instrument(linker, new WorkCompletionNotifier(task.getPath(), listener, LinkWorkItem::new));
		WorkResult result = BuildOperationLoggingCompilerDecorator.wrap(linker).execute(spec);
		task.setDidWork(result.getDidWork() || cleanedOutputs);
	}

	static @Nullable LinkTask.WorkItem workPlan(AbstractLinkTask task, LinkerSpec spec) {
		if (task.getSource().isEmpty()) {
			return null;
		}

		populate(task, spec, WorkPlanSupport.discardingOperationLogger(task.getTemporaryDir()));

		WorkPlanSupport.Recorder<LinkTask.WorkItem> recorder = new WorkPlanSupport.Recorder<>(LinkWorkItem::new);
		recorder.capture(newLinker(task, spec), spec);
		return recorder.getItems().get(0);
	}

	private static void populate(AbstractLinkTask task, LinkerSpec spec, BuildOperationLogger operationLogger) {
		spec.setTargetPlatform(task.getTargetPlatform().get());
		spec.setTempDir(task.getTemporaryDir());
		spec.setOutputFile(task.getLinkedFile().get().getAsFile());

		spec.objectFiles(task.getSource());
		spec.libraries(task.getLibs());
		spec.args(task.getLinkerArgs().get());
		spec.setDebuggable(task.getDebuggable().get());
		spec.setOperationLogger(operationLogger);
	}

	private static Compiler<LinkerSpec> newLinker(AbstractLinkTask task, LinkerSpec spec) {
		NativePlatformInternal targetPlatform = Cast.cast(NativePlatformInternal.class, task.getTargetPlatform().get());
		NativeToolChainInternal toolChain = Cast.cast(NativeToolChainInternal.class, task.getToolChain().get());
		Class<LinkerSpec> linkerSpecType = Cast.uncheckedCast(spec.getClass());
		return toolChain.select(targetPlatform).newCompiler(linkerSpecType);
	}
}
