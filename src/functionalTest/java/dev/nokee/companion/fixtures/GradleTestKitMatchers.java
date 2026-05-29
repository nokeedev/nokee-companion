package dev.nokee.companion.fixtures;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.List;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;

public class GradleTestKitMatchers {
	public static Matcher<GradleRunner> succeeds(Matcher<? super BuildResult> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(GradleRunner runner, Description mismatch) {
				BuildResult result = runner.build();
				if (!matcher.matches(result)) {
					matcher.describeMismatch(result, mismatch);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("build succeeds and ").appendDescriptionOf(matcher);
			}
		};
	}

	public static Matcher<GradleRunner> failure(Matcher<? super BuildResult> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(GradleRunner runner, Description mismatch) {
				BuildResult result = runner.buildAndFail();
				if (!matcher.matches(result)) {
					matcher.describeMismatch(result, mismatch);
					return false;
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("build fails and ").appendDescriptionOf(matcher);
			}
		};
	}

	public static Matcher<BuildResult> tasksExecuted(Matcher<? super Iterable<String>> matcher) {
		return new TasksExecuted(matcher);
	}

	public static Matcher<BuildResult> tasksSkipped(Matcher<? super Iterable<String>> matcher) {
		return new TasksSkipped(matcher);
	}

	public static Matcher<BuildResult> tasksExecutedAndNotSkipped(Matcher<? super Iterable<String>> matcher) {
		return new TasksExecutedAndNotSkipped(matcher);
	}

	private static final class TasksExecuted extends AbstractTaskMatcher {
		TasksExecuted(Matcher<? super Iterable<String>> matcher) {
			super("executed task paths of ", TasksExecuted::executedTaskPaths, matcher);
		}

		private static List<String> executedTaskPaths(BuildResult item) {
			return item.getTasks().stream().map(BuildTask::getPath).collect(toList());
		}
	}

	private static final class TasksSkipped extends AbstractTaskMatcher {
		TasksSkipped(Matcher<? super Iterable<String>> matcher) {
			super("skipped task paths of ", TasksSkipped::skippedTaskPaths, matcher);
		}

		private static List<String> skippedTaskPaths(BuildResult item) {
			return item.getTasks().stream().filter(TasksSkipped::isSkipped).map(BuildTask::getPath).collect(toList());
		}

		private static boolean isSkipped(BuildTask task) {
			return !(task.getOutcome().equals(TaskOutcome.SUCCESS) || task.getOutcome().equals(TaskOutcome.FAILED));
		}
	}

	private static final class TasksExecutedAndNotSkipped extends AbstractTaskMatcher {
		TasksExecutedAndNotSkipped(Matcher<? super Iterable<String>> matcher) {
			super("executed and not skipped task paths of ", TasksExecutedAndNotSkipped::getExecutedAndNotSkippedTaskPaths, matcher);
		}

		private static List<String> getExecutedAndNotSkippedTaskPaths(BuildResult item) {
			return item.getTasks().stream().filter(TasksExecutedAndNotSkipped::isExecutedAndNotSkipped).map(BuildTask::getPath).collect(toList());
		}

		private static boolean isExecutedAndNotSkipped(BuildTask buildTask) {
			return buildTask.getOutcome().equals(TaskOutcome.SUCCESS) || buildTask.getOutcome().equals(TaskOutcome.FAILED);
		}
	}

	private abstract static class AbstractTaskMatcher extends TypeSafeDiagnosingMatcher<BuildResult> {
		private final String description;
		private final Function<BuildResult, List<String>> extractTaskPaths;
		private final Matcher<? super Iterable<String>> matcher;

		AbstractTaskMatcher(String description, Function<BuildResult, List<String>> extractTaskPaths, Matcher<? super Iterable<String>> matcher) {
			this.description = description;
			this.extractTaskPaths = extractTaskPaths;
			this.matcher = matcher;
		}

		@Override
		protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
			List<String> actualTasks = extractTaskPaths.apply(item);
			if (!matcher.matches(actualTasks)) {
				matcher.describeMismatch(actualTasks, mismatchDescription);
				return false;
			}
			return true;
		}

		@Override
		public void describeTo(Description description) {
			description.appendText(this.description).appendDescriptionOf(matcher);
		}
	}

	public static Matcher<GradleRunner> becomesUpToDate() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(GradleRunner runner, Description mismatch) {
				runner.build();
				BuildResult result = runner.build();
				boolean matched = true;
				for (String arg : runner.getArguments()) {
					if (!arg.startsWith(":")) continue;
					TaskOutcome outcome = result.task(arg).getOutcome();
					if (outcome != TaskOutcome.UP_TO_DATE) {
						mismatch.appendText("task " + arg + " was " + outcome);
						matched = false;
					}
				}
				return matched;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("all tasks UP-TO-DATE on incremental build");
			}
		};
	}
}
