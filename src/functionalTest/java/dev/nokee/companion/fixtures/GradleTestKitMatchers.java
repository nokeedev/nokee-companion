package dev.nokee.companion.fixtures;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.hamcrest.Description;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Hamcrest matchers over the result of a <em>Gradle</em> build, decoupled from any specific runner.
 *
 * <p>The design separates three concerns:
 * <ul>
 *     <li><b>The seam</b> ({@link #theBuild(GradleRunner)}, {@link #from(BuildResult)}) adapts a concrete
 *         runner (or its result) into the opaque {@link ExecutedBuild}/{@link TheBuild} model. This is the
 *         only place that knows about a particular runner, so supporting a new one is a new overload here.</li>
 *     <li><b>The wrappers</b> ({@link #succeeds(GradleRunner)}, {@link #fails(GradleRunner)}) run the build,
 *         assert the coarse outcome, and hand back the opaque model so successive {@code assertThat}
 *         statements can read well.</li>
 *     <li><b>The matchers</b> ({@link #task}, {@link #tasksExecuted}, {@link #outcome}, {@link #output}, …)
 *         are declarative property checks over the opaque model, composable with the standard Hamcrest
 *         combinators ({@code not}, {@code allOf}, {@code hasItem}, {@code everyItem}, …).</li>
 * </ul>
 *
 * <p>The model is deliberately <em>opaque</em>: {@link ExecutedBuild} and {@link ExecutedTask} expose no
 * public accessors, so they can only ever be fed to a matcher — never used as a general-purpose build API.
 */
public final class GradleTestKitMatchers {
	private GradleTestKitMatchers() {}

	//region Normalized, opaque model (matching-only)

	/** Runner-agnostic task outcome. Adapters map their native outcome into this. */
	public enum TaskOutcome { SUCCESS, FAILED, UP_TO_DATE, SKIPPED, FROM_CACHE, NO_SOURCE }

	/** An executed task. Opaque: only matchers in this class can read it. */
	public static final class ExecutedTask {
		private final String path;
		private final TaskOutcome outcome;
		private final BuildResult source;

		private ExecutedTask(String path, TaskOutcome outcome, BuildResult source) {
			this.path = path;
			this.outcome = outcome;
			this.source = source;
		}

		private String path() {
			return path;
		}

		private TaskOutcome outcome() {
			return outcome;
		}

		private String output() {
			// NOTE: runnerkit is used here only because it already parses per-task Gradle output.
			// This is a temporary borrow to be extracted from the model later.
			return dev.gradleplugins.runnerkit.BuildResult.from(source.getOutput()).task(path).getOutput();
		}
	}

	/** The outcome of a completed build. Opaque: only matchers in this class can read it. */
	public static final class ExecutedBuild {
		private final BuildResult source;

		private ExecutedBuild(BuildResult source) {
			this.source = source;
		}

		private ExecutedTask taskOrNull(String path) {
			BuildTask task = source.task(path);
			if (task == null) {
				return null;
			}
			return new ExecutedTask(path, outcomeOf(task), source);
		}

		private List<ExecutedTask> tasks() {
			List<ExecutedTask> result = new ArrayList<>();
			for (BuildTask task : source.getTasks()) {
				result.add(new ExecutedTask(task.getPath(), outcomeOf(task), source));
			}
			return result;
		}

		private List<String> taskPaths() {
			return source.getTasks().stream().map(BuildTask::getPath).collect(toList());
		}

		private List<dev.gradleplugins.runnerkit.BuildResult.Failure> failures() {
			// NOTE: runnerkit borrowed only for its Gradle output parser; to be extracted later.
			return dev.gradleplugins.runnerkit.BuildResult.from(source.getOutput()).getFailures();
		}
	}

	private static TaskOutcome outcomeOf(BuildTask task) {
		switch (task.getOutcome()) {
			case SUCCESS: return TaskOutcome.SUCCESS;
			case FAILED: return TaskOutcome.FAILED;
			case UP_TO_DATE: return TaskOutcome.UP_TO_DATE;
			case SKIPPED: return TaskOutcome.SKIPPED;
			case FROM_CACHE: return TaskOutcome.FROM_CACHE;
			case NO_SOURCE: return TaskOutcome.NO_SOURCE;
			default: throw new IllegalStateException("unknown task outcome: " + task.getOutcome());
		}
	}
	//endregion

	//region Adapters / seam

	/** Adapts an already-executed result into the opaque model. */
	public static ExecutedBuild from(BuildResult result) {
		return new ExecutedBuild(result);
	}

	/** Adapts a runner into an executable, opaque subject. The only place that references the runner type. */
	public static TheBuild theBuild(GradleRunner runner) {
		return new TheBuild(runner);
	}

	/** A build that has not run yet. Opaque: only wrappers/matchers in this class can drive it. */
	public static final class TheBuild {
		private final GradleRunner runner;

		private TheBuild(GradleRunner runner) {
			this.runner = runner;
		}

		private ExecutedBuild build() {
			return new ExecutedBuild(runner.build());
		}

		private ExecutedBuild buildAndFail() {
			return new ExecutedBuild(runner.buildAndFail());
		}

		private List<String> arguments() {
			return runner.getArguments();
		}
	}
	//endregion

	//region Wrappers (run + assert coarse outcome + return opaque model)

	/** Runs the build, asserting it succeeds, and returns its result for further assertions. */
	public static ExecutedBuild succeeds(GradleRunner runner) {
		return theBuild(runner).build();
	}

	/** Runs the build, asserting it fails, and returns its result for further assertions. */
	public static ExecutedBuild fails(GradleRunner runner) {
		return theBuild(runner).buildAndFail();
	}
	//endregion

	//region Runner matcher

	/**
	 * Matches a build whose requested tasks are all {@code UP-TO-DATE} when run a second time.
	 * Builds twice to reach a steady state, then checks the tasks named on the command line: an argument
	 * starting with {@code :} is an absolute task path, while any other non-flag argument (not starting
	 * with {@code -}) is a relative task path. Task name compression (abbreviated names) is not expanded.
	 */
	public static Matcher<TheBuild> becomesUpToDate() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(TheBuild subject, Description mismatch) {
				subject.build(); // reach steady state
				ExecutedBuild second = subject.build();

				List<ExecutedTask> requested = new ArrayList<>();
				for (ExecutedTask task : second.tasks()) {
					if (isRequestedTask(task.path(), subject.arguments())) {
						requested.add(task);
					}
				}

				if (requested.isEmpty()) {
					mismatch.appendText("no requested task ran; arguments were ").appendValue(subject.arguments());
					return false;
				}

				boolean matched = true;
				for (ExecutedTask task : requested) {
					if (task.outcome() != TaskOutcome.UP_TO_DATE) {
						mismatch.appendText("task ").appendValue(task.path()).appendText(" was ").appendValue(task.outcome()).appendText("; ");
						matched = false;
					}
				}
				return matched;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("the requested tasks UP-TO-DATE on an incremental build");
			}
		};
	}

	private static boolean isRequestedTask(String taskPath, List<String> arguments) {
		for (String arg : arguments) {
			if (arg.startsWith("-")) {
				continue; // flag
			}
			if (arg.startsWith(":")) {
				// absolute task path
				if (taskPath.equals(arg)) {
					return true;
				}
			} else if (taskPath.equals(":" + arg) || taskPath.endsWith(":" + arg)) {
				// relative task path (task name compression is not expanded)
				return true;
			}
		}
		return false;
	}
	//endregion

	//region Task selection

	/** Locates a single task by path and applies the given task matcher to it. */
	public static Matcher<ExecutedBuild> task(Object taskPath, Matcher<? super ExecutedTask> matcher) {
		String path = taskPath.toString();
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedBuild build, Description mismatch) {
				ExecutedTask task = build.taskOrNull(path);
				if (task == null) {
					mismatch.appendText("no task ").appendValue(path).appendText(" in build; tasks were ").appendValue(build.taskPaths());
					return false;
				}
				if (!matcher.matches(task)) {
					mismatch.appendText("task ").appendValue(path).appendText(" ");
					matcher.describeMismatch(task, mismatch);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("build with task ").appendValue(path).appendText(" that ").appendDescriptionOf(matcher);
			}
		};
	}
	//endregion

	//region Task matchers (over ExecutedTask)

	public static Matcher<ExecutedTask> outcome(Matcher<? super TaskOutcome> matcher) {
		return new FeatureMatcher<ExecutedTask, TaskOutcome>(matcher, "a task with outcome", "outcome") {
			@Override
			protected TaskOutcome featureValueOf(ExecutedTask actual) {
				return actual.outcome();
			}
		};
	}

	public static Matcher<ExecutedTask> output(Matcher<? super String> matcher) {
		return new FeatureMatcher<ExecutedTask, String>(matcher, "a task with output", "output") {
			@Override
			protected String featureValueOf(ExecutedTask actual) {
				return actual.output();
			}
		};
	}

	/** Path-aware output matcher, for messages that embed the task path. */
	public static Matcher<ExecutedTask> output(Function<String, Matcher<? super String>> byPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedTask task, Description mismatch) {
				Matcher<? super String> matcher = byPath.apply(task.path());
				if (!matcher.matches(task.output())) {
					matcher.describeMismatch(task.output(), mismatch);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("a task whose output matches a path-derived matcher");
			}
		};
	}

	public static Matcher<ExecutedTask> executed() {
		return outcome(anyOf(is(TaskOutcome.SUCCESS), is(TaskOutcome.FAILED)));
	}

	public static Matcher<ExecutedTask> skipped() {
		return not(executed());
	}

	public static Matcher<ExecutedTask> upToDate() {
		return outcome(is(TaskOutcome.UP_TO_DATE));
	}

	public static Matcher<ExecutedTask> fromCache() {
		return outcome(is(TaskOutcome.FROM_CACHE));
	}

	public static Matcher<ExecutedTask> noSource() {
		return outcome(is(TaskOutcome.NO_SOURCE));
	}

	public static Matcher<ExecutedTask> failed() {
		return outcome(is(TaskOutcome.FAILED));
	}

	public static Matcher<ExecutedTask> performsFullRebuild() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedTask task, Description mismatch) {
				// TODO: also assert the message is logged at INFO level
				String needle = String.format("The input changes require a full rebuild for incremental task '%s'.", task.path());
				if (!task.output().contains(needle)) {
					mismatch.appendText("task ").appendValue(task.path()).appendText(" did not perform a full rebuild");
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("a task that performs a full rebuild");
			}
		};
	}
	//endregion

	//region Task-set matchers (over ExecutedBuild)

	public static Matcher<ExecutedBuild> tasksExecuted(Matcher<? super Iterable<String>> matcher) {
		return taskPaths("executed task paths of ", task -> true, matcher);
	}

	public static Matcher<ExecutedBuild> tasksExecutedAndNotSkipped(Matcher<? super Iterable<String>> matcher) {
		return taskPaths("executed and not skipped task paths of ", GradleTestKitMatchers::isExecutedAndNotSkipped, matcher);
	}

	public static Matcher<ExecutedBuild> tasksSkipped(Matcher<? super Iterable<String>> matcher) {
		return taskPaths("skipped task paths of ", task -> !isExecutedAndNotSkipped(task), matcher);
	}

	public static Matcher<ExecutedBuild> tasksExecutedAndFromCache(Matcher<? super Iterable<String>> matcher) {
		return taskPaths("executed and from cache task paths of ", task -> task.outcome() == TaskOutcome.FROM_CACHE, matcher);
	}

	private static boolean isExecutedAndNotSkipped(ExecutedTask task) {
		return task.outcome() == TaskOutcome.SUCCESS || task.outcome() == TaskOutcome.FAILED;
	}

	private static Matcher<ExecutedBuild> taskPaths(String description, Predicate<ExecutedTask> filter, Matcher<? super Iterable<String>> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedBuild build, Description mismatch) {
				List<String> paths = build.tasks().stream().filter(filter).map(ExecutedTask::path).collect(toList());
				if (!matcher.matches(paths)) {
					matcher.describeMismatch(paths, mismatch);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description2) {
				description2.appendText(description).appendDescriptionOf(matcher);
			}
		};
	}
	//endregion

	//region Failure matchers (over ExecutedBuild)

	public static Matcher<ExecutedBuild> hasFailureCause(String cause) {
		return hasFailureCause(Matchers.startsWith(cause));
	}

	public static Matcher<ExecutedBuild> hasFailureCause(Matcher<? super String> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedBuild build, Description mismatch) {
				List<String> causes = new ArrayList<>();
				for (dev.gradleplugins.runnerkit.BuildResult.Failure failure : build.failures()) {
					causes.addAll(failure.getCauses());
				}
				for (String cause : causes) {
					if (matcher.matches(cause)) {
						return true;
					}
				}
				mismatch.appendValueList("none of the following causes matches: ", ", ", "", causes);
				return false;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("a failure cause matching ").appendDescriptionOf(matcher);
			}
		};
	}

	public static Matcher<ExecutedBuild> hasFailureDescription(String description) {
		return hasFailureDescription(Matchers.startsWith(description));
	}

	public static Matcher<ExecutedBuild> hasFailureDescription(Matcher<? super String> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(ExecutedBuild build, Description mismatch) {
				List<String> descriptions = new ArrayList<>();
				for (dev.gradleplugins.runnerkit.BuildResult.Failure failure : build.failures()) {
					if (matcher.matches(failure.getDescription())) {
						return true;
					}
					descriptions.add(failure.getDescription());
				}
				mismatch.appendValueList("none of the following descriptions matches: ", ", ", "", descriptions);
				return false;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("a failure description matching ").appendDescriptionOf(matcher);
			}
		};
	}
	//endregion
}
