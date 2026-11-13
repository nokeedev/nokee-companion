package dev.nokee.nativeplatform.tasks.internal;

import dev.nokee.work.WorkCompletionListener;
import org.gradle.nativeplatform.toolchain.internal.CommandLineToolInvocation;

import java.util.function.BiFunction;

public final class WorkCompletionNotifier {
	private final String taskPath;
	private final WorkCompletionListener listener;
	private final BiFunction<String, CommandLineToolInvocation, ?> workItemFactory;

	public WorkCompletionNotifier(String taskPath, WorkCompletionListener listener, BiFunction<String, CommandLineToolInvocation, ?> workItemFactory) {
		this.taskPath = taskPath;
		this.listener = listener;
		this.workItemFactory = workItemFactory;
	}

	public Execution newExecution(String executable, CommandLineToolInvocation invocation) {
		return new Execution(workItemFactory.apply(executable, invocation), invocation.description().build().getDisplayName());
	}

	public final class Execution {
		private final Object workItem;
		private final String displayName;

		private Execution(Object workItem, String displayName) {
			this.workItem = workItem;
			this.displayName = displayName;
		}

		public <E extends Exception> void run(Work<E> work) throws E {
			long startTime = System.currentTimeMillis();
			try {
				work.run();
			} catch (Throwable failure) {
				listener.onFinish(new DefaultEvent(new DefaultFailure(startTime, System.currentTimeMillis(), failure)));
				throw failure;
			}
			listener.onFinish(new DefaultEvent(new DefaultSuccess(startTime, System.currentTimeMillis())));
		}

		private final class DefaultEvent implements WorkCompletionListener.Event {
			private final WorkCompletionListener.Result result;

			private DefaultEvent(WorkCompletionListener.Result result) {
				this.result = result;
			}

			@Override
			public long getEventTime() {
				return result.getEndTime();
			}

			@Override
			public String getDisplayName() {
				return displayName + (result instanceof WorkCompletionListener.Result.Failure ? " FAILED" : " SUCCESS");
			}

			@Override
			public String getTaskPath() {
				return taskPath;
			}

			@Override
			public Object getWorkItem() {
				return workItem;
			}

			@Override
			public WorkCompletionListener.Result getResult() {
				return result;
			}

			@Override
			public String toString() {
				return getDisplayName();
			}
		}
	}

	@FunctionalInterface
	public interface Work<E extends Exception> {
		void run() throws E;
	}

	private static class DefaultResult implements WorkCompletionListener.Result {
		private final long startTime;
		private final long endTime;

		private DefaultResult(long startTime, long endTime) {
			this.startTime = startTime;
			this.endTime = endTime;
		}

		@Override
		public long getStartTime() {
			return startTime;
		}

		@Override
		public long getEndTime() {
			return endTime;
		}
	}

	private static final class DefaultSuccess extends DefaultResult implements WorkCompletionListener.Result.Success {
		private DefaultSuccess(long startTime, long endTime) {
			super(startTime, endTime);
		}
	}

	private static final class DefaultFailure extends DefaultResult implements WorkCompletionListener.Result.Failure {
		private final Throwable exception;

		private DefaultFailure(long startTime, long endTime, Throwable exception) {
			super(startTime, endTime);
			this.exception = exception;
		}

		@Override
		public Throwable getException() {
			return exception;
		}
	}
}
