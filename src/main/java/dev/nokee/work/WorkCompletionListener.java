package dev.nokee.work;

import org.gradle.api.Incubating;

/**
 * A listener notified when the work items of a task complete.
 *
 * <p>Each task annotated with {@link EmitsWorkCompletionEvents} looks up the shared build service named {@value #SERVICE_NAME}.
 * When this service implements this interface, the task calls {@link #onFinish(Event)} once per executed work item.
 * When no service has this name, or the service does not implement this interface, the task notifies nothing.</p>
 *
 * <p>Only executed work items produce an event, so an up-to-date task notifies nothing.
 * Events can arrive concurrently, from multiple tasks and from multiple work items of the same task.</p>
 *
 * <p>The following build script registers a listener printing each completed work item:</p>
 *
 * <pre>{@code
 * import dev.nokee.work.WorkCompletionListener
 *
 * abstract class PrintCompletedWork implements BuildService<BuildServiceParameters.None>, WorkCompletionListener {
 *     void onFinish(WorkCompletionListener.Event event) {
 *         println "${event.taskPath} ${event.displayName}"
 *     }
 * }
 *
 * gradle.sharedServices.registerIfAbsent(WorkCompletionListener.SERVICE_NAME, PrintCompletedWork) {}
 * }</pre>
 */
@Incubating
public interface WorkCompletionListener {
	/**
	 * The name of the shared build service the tasks look up.
	 */
	String SERVICE_NAME = "nokeeWorkCompletionListener";

	/**
	 * Called when a work item completes.
	 *
	 * @param event  the work item completion
	 */
	void onFinish(Event event);

	/**
	 * An event informing about a work item having finished its execution.
	 */
	interface Event {
		/**
		 * {@return the time of this event, in milliseconds since the epoch}
		 */
		long getEventTime();

		/**
		 * {@return a human-readable description of this event}
		 */
		String getDisplayName();

		/**
		 * {@return the path of the task executing the work item}
		 */
		String getTaskPath();

		/**
		 * {@return the completed work item}
		 * The task defines its work item type, the same type its {@link WorkPlan work plan} holds.
		 */
		Object getWorkItem();

		/**
		 * {@return the result of the work item execution}
		 */
		Result getResult();
	}

	/**
	 * The result of a work item execution.
	 */
	interface Result {
		/**
		 * {@return the time the work item started, in milliseconds since the epoch}
		 */
		long getStartTime();

		/**
		 * {@return the time the work item finished, in milliseconds since the epoch}
		 */
		long getEndTime();

		/**
		 * The result of a successful work item execution.
		 */
		interface Success extends Result {}

		/**
		 * The result of a failed work item execution.
		 */
		interface Failure extends Result {
			/**
			 * {@return the exception that failed the work item}
			 */
			Throwable getException();
		}
	}
}
