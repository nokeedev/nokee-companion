package dev.nokee.nativeplatform.tasks.internal;

import dev.nokee.work.WorkCompletionListener;
import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.internal.operations.*;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.nativeplatform.toolchain.internal.AbstractCompiler;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.Field;

import static dev.nokee.companion.features.ReflectionUtils.*;

public final class WorkListenerSupport {
	private static final Logger LOGGER = Logging.getLogger(WorkListenerSupport.class);
	private static final String LISTENER_DOCUMENTATION = "https://github.com/nokeedev/nokee-companion/blob/main/src/main/java/dev/nokee/work/WorkCompletionListener.java";

	private WorkListenerSupport() {}

	@SuppressWarnings("rawtypes")
	public static @Nullable WorkCompletionListener asListener(String taskPath, Provider<? extends BuildService> service) {
		Object result = service.getOrNull();
		if (result instanceof WorkCompletionListener) {
			return (WorkCompletionListener) result;
		} else if (result != null) {
			LOGGER.warn("The build service registered as '{}' does not implement {}, so task '{}' cannot emit work completion events. See {} for an example.",
				WorkCompletionListener.SERVICE_NAME, WorkCompletionListener.class.getName(), taskPath, LISTENER_DOCUMENTATION);
		}
		return null;
	}

	public static void instrument(Compiler<?> compiler, WorkCompletionNotifier notifier) {
		try {
			AbstractCompiler<?> target = unwrap(compiler);
			if (target == null) {
				LOGGER.info("Could not find the native tool for '{}', work completion listener will not be notified.", compiler);
				return;
			}

			Field field = getField(AbstractCompiler.class, "buildOperationExecutor");
			BuildOperationExecutor delegate = readFieldValue(field, target);
			if (!(delegate instanceof NotifyingBuildOperationExecutor)) {
				updateFieldValue(field, target, new NotifyingBuildOperationExecutor(delegate, notifier));
			}
		} catch (Throwable e) {
			LOGGER.info("Could not patch the native tool, work completion listener will not be notified.", e);
		}
	}

	public static @Nullable AbstractCompiler<?> unwrap(Compiler<?> compiler) {
		Object current = compiler;
		while (current != null && !(current instanceof AbstractCompiler)) {
			Field field = findField(current.getClass(), "compiler");
			if (field == null) {
				return null;
			}
			current = readFieldValue(field, current);
		}
		return (AbstractCompiler<?>) current;
	}

	public static final class NotifyingWorker<O extends BuildOperation> implements BuildOperationWorker<O> {
		private final BuildOperationWorker<O> delegate;
		private final String executable;
		private final WorkCompletionNotifier notifier;

		private NotifyingWorker(BuildOperationWorker<O> delegate, WorkCompletionNotifier notifier) {
			this.delegate = delegate;
			this.executable = executableOf(delegate);
			this.notifier = notifier;
		}

		public BuildOperationWorker<O> getDelegate() {
			return delegate;
		}

		public WorkCompletionNotifier.Execution newExecution(CommandLineToolInvocation invocation) {
			return notifier.newExecution(executable, invocation);
		}

		@Override
		public void execute(O operation, BuildOperationContext context) throws Exception {
			newExecution((CommandLineToolInvocation) operation).run(() -> delegate.execute(operation, context));
		}
	}

	private static final class NotifyingBuildOperationExecutor implements BuildOperationExecutor {
		private final BuildOperationExecutor delegate;
		private final WorkCompletionNotifier notifier;

		private NotifyingBuildOperationExecutor(BuildOperationExecutor delegate, WorkCompletionNotifier notifier) {
			this.delegate = delegate;
			this.notifier = notifier;
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action) {
			delegate.runAll(action);
		}

		@Override
		public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			delegate.runAll(action, buildOperationConstraint);
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action) {
			delegate.runAllWithAccessToProjectState(action);
		}

		@Override
		public <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			delegate.runAllWithAccessToProjectState(action, buildOperationConstraint);
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> action) {
			delegate.runAll(new NotifyingWorker<>(worker, notifier), action);
		}

		@Override
		public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> action, BuildOperationConstraint buildOperationConstraint) {
			delegate.runAll(new NotifyingWorker<>(worker, notifier), action, buildOperationConstraint);
		}

		public BuildOperationRef getCurrentOperation() {
			return delegate.getCurrentOperation();
		}
	}

	static String executableOf(BuildOperationWorker<?> worker) {
		Field field = findField(worker.getClass(), "executable");
		if (field != null) {
			Object value = readFieldValue(field, worker);
			if (value instanceof File) {
				return ((File) value).getAbsolutePath();
			} else if (value != null) {
				return value.toString();
			}
		}
		return String.valueOf(worker);
	}
}
