package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.internal.ToolInvocationWorkItem;
import dev.nokee.nativeplatform.tasks.internal.WorkCompletionNotifier;
import dev.nokee.nativeplatform.tasks.internal.WorkListenerAware;
import dev.nokee.nativeplatform.tasks.internal.WorkListenerSupport;
import dev.nokee.nativeplatform.tasks.internal.WorkPlanSupport;
import dev.nokee.work.WorkCompletionListener;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.Cast;
import org.gradle.internal.operations.logging.BuildOperationLogger;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.internal.BuildOperationLoggingCompilerDecorator;
import org.gradle.nativeplatform.internal.DefaultStaticLibraryArchiverSpec;
import org.gradle.nativeplatform.internal.StaticLibraryArchiverSpec;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;

/*private*/ abstract /*final*/ class ArTask extends org.gradle.nativeplatform.tasks.CreateStaticLibrary implements Ar, WorkListenerAware {
	@Inject
	public ArTask() {}

	@Override
	public Provider<Ar.WorkItem> getWorkPlan() {
		return getSource().getElements().map(source -> workPlan());
	}

	private @Nullable Ar.WorkItem workPlan() {
		if (getSource().isEmpty()) {
			return null;
		}

		StaticLibraryArchiverSpec spec = newSpec(new PlannedOutputFile(getOutputFile().get().getAsFile()), WorkPlanSupport.discardingOperationLogger(getTemporaryDir()));

		WorkPlanSupport.Recorder<Ar.WorkItem> recorder = new WorkPlanSupport.Recorder<>(ArWorkItem::new);
		recorder.capture(newArchiver(), spec);
		return recorder.getItems().get(0);
	}

	@Override
	protected void link() {
		WorkCompletionListener listener = workListener();
		if (listener == null) {
			super.link();
		} else {
			archive(listener);
		}
	}

	// Copied from CreateStaticLibrary#link with support for work completion listener
	private void archive(WorkCompletionListener listener) {
		StaticLibraryArchiverSpec spec = newSpec(getOutputFile().get().getAsFile(), getOperationLoggerFactory().newOperationLogger(getName(), getTemporaryDir()));

		Compiler<StaticLibraryArchiverSpec> archiver = newArchiver();
		WorkListenerSupport.instrument(archiver, new WorkCompletionNotifier(getPath(), listener, ArWorkItem::new));
		WorkResult result = BuildOperationLoggingCompilerDecorator.wrap(archiver).execute(spec);
		setDidWork(result.getDidWork());
	}

	private StaticLibraryArchiverSpec newSpec(File outputFile, BuildOperationLogger operationLogger) {
		StaticLibraryArchiverSpec spec = new DefaultStaticLibraryArchiverSpec();
		spec.setTempDir(getTemporaryDir());
		spec.setOutputFile(outputFile);
		spec.objectFiles(getSource());
		spec.args(getStaticLibArgs().get());
		spec.setOperationLogger(operationLogger);
		return spec;
	}

	private Compiler<StaticLibraryArchiverSpec> newArchiver() {
		NativePlatformInternal targetPlatform = Cast.cast(NativePlatformInternal.class, getTargetPlatform().get());
		NativeToolChainInternal toolChain = Cast.cast(NativeToolChainInternal.class, getToolChain().get());
		return toolChain.select(targetPlatform).newCompiler(StaticLibraryArchiverSpec.class);
	}

	private static final class PlannedOutputFile extends File {
		private PlannedOutputFile(File outputFile) {
			super(outputFile.getPath());
		}

		@Override
		public boolean delete() {
			return true;
		}
	}

	static final class ArWorkItem extends ToolInvocationWorkItem implements Ar.WorkItem {
		ArWorkItem(String executable, CommandLineToolInvocation invocation) {
			super(executable, invocation);
		}
	}
}
