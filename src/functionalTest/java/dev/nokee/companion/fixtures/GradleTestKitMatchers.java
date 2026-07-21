package dev.nokee.companion.fixtures;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class GradleTestKitMatchers {
	public static Matcher<GradleRunner> succeeds(Matcher<? super BuildResult> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(GradleRunner runner, Description mismatch) {
				assert runner.getArguments().contains("--stacktrace");
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

	public static Matcher<GradleRunner> fails(Matcher<? super BuildResult> matcher) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(GradleRunner runner, Description mismatch) {
				assert runner.getArguments().contains("--stacktrace");
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

	public static Matcher<BuildResult> taskExecutedAndNotSkipped(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
				return isExecutedAndNotSkipped(item.task(taskPath.toString()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> taskExecutedAndFromCache(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
				return isExecutedAndFromCache(item.task(taskPath.toString()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> taskExecutedAndUpToDate(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
				return item.task(taskPath.toString()).getOutcome().equals(TaskOutcome.UP_TO_DATE);
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> taskSkippedBecauseNoSource(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
				return item.task(taskPath.toString()).getOutcome().equals(TaskOutcome.NO_SOURCE);
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static TaskSkippedMatcher taskSkipped(Object taskPath) {
		return new TaskSkippedMatcher() {
			@Override
			public Matcher<BuildResult> becauseNoSource() {
				return new TypeSafeDiagnosingMatcher<BuildResult>() {
					@Override
					protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
						return item.task(taskPath.toString()).getOutcome().equals(TaskOutcome.NO_SOURCE);
					}

					@Override
					public void describeTo(Description description) {

					}
				};
			}

			@Override
			public Matcher<BuildResult> becauseUpToDate() {
				return new TypeSafeDiagnosingMatcher<BuildResult>() {
					@Override
					protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
						return item.task(taskPath.toString()).getOutcome().equals(TaskOutcome.UP_TO_DATE);
					}

					@Override
					public void describeTo(Description description) {

					}
				};
			}

			@Override
			public Matcher<BuildResult> forAnyReason() {
				return new TypeSafeDiagnosingMatcher<>() {
					@Override
					protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
						return !isExecutedAndNotSkipped(item.task(taskPath.toString()));
					}

					@Override
					public void describeTo(Description description) {

					}
				};
			}
		};
	}

	public interface TaskSkippedMatcher {
		Matcher<BuildResult> becauseNoSource();
		Matcher<BuildResult> becauseUpToDate();
		Matcher<BuildResult> forAnyReason();
	}

	public static Matcher<BuildResult> taskFailed(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
				return item.task(taskPath.toString()).getOutcome().equals(TaskOutcome.FAILED);
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> tasksExecutedAndFromCache(Matcher<? super Iterable<String>> matcher) {
		return new TasksExecutedAndFromCache(matcher);
	}

	private static final class TasksExecutedAndFromCache extends AbstractTaskMatcher {
		TasksExecutedAndFromCache(Matcher<? super Iterable<String>> matcher) {
			super("executed and from cache task paths of ", TasksExecutedAndFromCache::getExecutedAndFromCacheTaskPaths, matcher);
		}

		private static List<String> getExecutedAndFromCacheTaskPaths(BuildResult item) {
			return item.getTasks().stream().filter(GradleTestKitMatchers::isExecutedAndFromCache).map(BuildTask::getPath).collect(toList());
		}
	}

	private static boolean isExecutedAndFromCache(BuildTask buildTask) {
		return buildTask.getOutcome().equals(TaskOutcome.FROM_CACHE);
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
			return item.getTasks().stream().filter(GradleTestKitMatchers::isExecutedAndNotSkipped).map(BuildTask::getPath).collect(toList());
		}
	}

	private static boolean isExecutedAndNotSkipped(BuildTask buildTask) {
		return buildTask.getOutcome().equals(TaskOutcome.SUCCESS) || buildTask.getOutcome().equals(TaskOutcome.FAILED);
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

	public static Matcher<BuildResult> hasFailureCause(String cause) {
		return hasFailureCause(Matchers.startsWith(cause));
	}

	public static Matcher<BuildResult> hasFailureCause(Matcher<? super String> matcher) {
		return new FailureCause(matcher);
	}

	public static Matcher<BuildResult> hasFailureDescription(String description) {
		return hasFailureDescription(Matchers.startsWith(description));
	}

	public static Matcher<BuildResult> hasFailureDescription(Matcher<? super String> matcher) {
		return new FailureDescription(matcher);
	}

	private static final class FailureCause extends TypeSafeDiagnosingMatcher<BuildResult> {
		private final Matcher<? super String> matcher;

		FailureCause(Matcher<? super String> matcher) {
			this.matcher = matcher;
		}

		protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
			dev.gradleplugins.runnerkit.BuildResult result = dev.gradleplugins.runnerkit.BuildResult.from(item.getOutput());
			for(dev.gradleplugins.runnerkit.BuildResult.Failure failure : result.getFailures()) {
				for(String cause : failure.getCauses()) {
					if (this.matcher.matches(cause)) {
						return true;
					}
				}
			}

			mismatchDescription.appendValueList("none of the following causes matches: ", ", ", "", (Iterable)result.getFailures().stream().flatMap((it) -> it.getCauses().stream()).distinct().collect(Collectors.toList()));
			return false;
		}

		public void describeTo(Description description) {
			description.appendText("a failure cause matching ").appendDescriptionOf(this.matcher);
		}
	}

	private static final class FailureDescription extends TypeSafeDiagnosingMatcher<BuildResult> {
		private final Matcher<? super String> matcher;

		FailureDescription(Matcher<? super String> matcher) {
			this.matcher = matcher;
		}

		public void describeTo(Description description) {
			description.appendText("a failure description matching ").appendDescriptionOf(this.matcher);
		}

		protected boolean matchesSafely(BuildResult item, Description mismatchDescription) {
			dev.gradleplugins.runnerkit.BuildResult result = dev.gradleplugins.runnerkit.BuildResult.from(item.getOutput());
			for(dev.gradleplugins.runnerkit.BuildResult.Failure failure : result.getFailures()) {
				if (this.matcher.matches(failure.getDescription())) {
					return true;
				}
			}

			mismatchDescription.appendValueList("none of the following description matches: ", ", ", "", (Iterable)result.getFailures().stream().map(dev.gradleplugins.runnerkit.BuildResult.Failure::getDescription).distinct().collect(Collectors.toList()));
			return false;
		}
	}

	public static Matcher<BuildResult> performFullRebuildForIncrementalTask(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult item, Description description) {
				dev.gradleplugins.runnerkit.BuildResult result = dev.gradleplugins.runnerkit.BuildResult.from(item.getOutput());
				// TODO: Check the output is info
				return result.task(taskPath.toString()).getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", taskPath.toString()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildTaskOutput> performFullRebuildForIncrementalTask() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildTaskOutput item, Description description) {
				// TODO: Check the output is info
				return item.getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", item.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> taskPerformsIncrementalBuild(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult result, Description description) {
				BuildTaskOutput item = tasksOutput(result).task(taskPath);
				// TODO: Check the output is info
				return !item.getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", item.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildResult> taskPerformsFullRebuild(Object taskPath) {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildResult result, Description description) {
				BuildTaskOutput item = tasksOutput(result).task(taskPath);
				// TODO: Check the output is info
				return item.getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", item.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildTaskOutput> taskPerformsFullRebuild() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildTaskOutput item, Description description) {
				// TODO: Check the output is info
				return item.getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", item.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildTaskOutput> outputContains(Function<String, String> mapper) {
		return new TypeSafeDiagnosingMatcher<BuildTaskOutput>() {
			@Override
			protected boolean matchesSafely(BuildTaskOutput item, Description mismatchDescription) {
				return item.getOutput().contains(mapper.apply(item.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static Matcher<BuildTaskOutput> outputContains(String substring) {
		return new TypeSafeDiagnosingMatcher<BuildTaskOutput>() {
			@Override
			protected boolean matchesSafely(BuildTaskOutput item, Description mismatchDescription) {
				return item.getOutput().contains(substring);
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}

	public static PerTaskOutput tasksOutput(BuildResult result) {
		return new PerTaskOutput() {
			@Override
			public BuildTaskOutput task(Object taskPath) {
				return new BuildTaskOutput() {
					@Override
					public String getOutput() {
						return dev.gradleplugins.runnerkit.BuildResult.from(result.getOutput()).task(taskPath.toString()).getOutput();
					}

					@Override
					public String getPath() {
						return taskPath.toString();
					}

					@Override
					public String toString() {
						return getOutput();
					}
				};
			}
		};
	}

	public interface PerTaskOutput {
		BuildTaskOutput task(Object taskPath);
	}

	public interface BuildTaskOutput {
		String getOutput();
		String getPath();
	}
}
