package dev.nokee.companion.fixtures;

import org.gradle.testkit.runner.BuildResult;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.gradleplugins.buildscript.syntax.Syntax.groovyDsl;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

public final class WorkObservabilityFixtures {
	private WorkObservabilityFixtures() {}

	//region Build configuration
	public static Consumer<GradleBuild.GradleProject> withWorkItemLines() {
		return project -> {
			project.append(groovyDsl("""
				class WorkItemLines {
					static String of(workItem) {
						def commandLine = [workItem.executable] + workItem.arguments.collectMany { argument ->
							argument.startsWith('@') ? [argument] + workItem.files[argument.substring(1)].readLines() : [argument]
						}
						return commandLine.join(' ')
					}
				}
			"""));
		};
	}

	public static Consumer<GradleBuild.GradleProject> withWorkListener() {
		return project -> {
			project.append(groovyDsl("""
				abstract class PrintingWorkCompletionListener implements BuildService<BuildServiceParameters.None>, WorkCompletionListener {
					@Override
					void onFinish(WorkCompletionListener.Event event) {
						def outcome = event.result instanceof WorkCompletionListener.Result.Success ? 'SUCCESS' : 'FAILED'
						println "WORK EVENT ${event.taskPath} ${outcome} ${WorkItemLines.of(event.workItem)}"
					}
				}
				gradle.sharedServices.registerIfAbsent(WorkCompletionListener.SERVICE_NAME, PrintingWorkCompletionListener) {}
			"""));
		};
	}
	//endregion

	//region Observed work (matching-only)
	public static List<ObservedWorkItem> theWorkPlan(BuildResult result) {
		return result.getOutput().lines()
			.filter(it -> it.startsWith("WORK PLAN "))
			.map(ObservedWorkItem::parsePlanned)
			.collect(Collectors.toList());
	}

	public static ObservedWorkItems theEvents(BuildResult result) {
		return new ObservedWorkItems(result.getOutput().lines()
			.filter(it -> it.startsWith("WORK EVENT "))
			.map(ObservedWorkItem::parseCompleted)
			.collect(Collectors.toList()));
	}

	public static final class ObservedWorkItems extends AbstractList<ObservedWorkItem> {
		private final List<ObservedWorkItem> workItems;

		private ObservedWorkItems(List<ObservedWorkItem> workItems) {
			this.workItems = workItems;
		}

		public ObservedWorkItems forTask(String taskPath) {
			return new ObservedWorkItems(workItems.stream()
				.filter(it -> it.taskPath.equals(taskPath))
				.collect(Collectors.toList()));
		}

		@Override
		public ObservedWorkItem get(int index) {
			return workItems.get(index);
		}

		@Override
		public int size() {
			return workItems.size();
		}
	}

	public static final class ObservedWorkItem {
		private final String taskPath;
		private final String outcome;
		private final List<String> commandLine;

		private ObservedWorkItem(String taskPath, String outcome, List<String> commandLine) {
			this.taskPath = taskPath;
			this.outcome = outcome;
			this.commandLine = commandLine;
		}

		private static ObservedWorkItem parsePlanned(String line) {
			String[] fields = line.split(" ", 3);
			return new ObservedWorkItem(null, null, Arrays.asList(fields[2].split(" ")));
		}

		private static ObservedWorkItem parseCompleted(String line) {
			String[] fields = line.split(" ", 5);
			return new ObservedWorkItem(fields[2], fields[3], Arrays.asList(fields[4].split(" ")));
		}

		@Override
		public String toString() {
			return Stream.concat(Stream.of(taskPath, outcome).filter(Objects::nonNull), commandLine.stream()).collect(Collectors.joining(" "));
		}
	}
	//endregion

	//region Matchers
	public static Matcher<ObservedWorkItem> compilationOf(String sourceFile) {
		return withArgument(endsWith("/src/main/cpp/" + sourceFile));
	}

	public static Matcher<ObservedWorkItem> linkOf(String linkedFile) {
		return withArgument(endsWith("/" + linkedFile));
	}

	public static Matcher<ObservedWorkItem> withArgument(String argument) {
		return withArgument(equalTo(argument));
	}

	public static Matcher<ObservedWorkItem> withArgument(Matcher<? super String> matcher) {
		return new FeatureMatcher<ObservedWorkItem, List<String>>(hasItem(matcher), "a work item whose command line", "command line") {
			@Override
			protected List<String> featureValueOf(ObservedWorkItem actual) {
				return actual.commandLine;
			}
		};
	}

	public static Matcher<ObservedWorkItem> failure() {
		return new FeatureMatcher<ObservedWorkItem, String>(equalTo("FAILED"), "a work item whose outcome", "outcome") {
			@Override
			protected String featureValueOf(ObservedWorkItem actual) {
				return actual.outcome;
			}
		};
	}
	//endregion
}
