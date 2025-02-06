package dev.nokee.companion;

import dev.gradleplugins.exemplarkit.Step;
import dev.gradleplugins.exemplarkit.StepExecutionResult;
import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.BuildTask;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class ExemplarMatchers {
	public static Matcher<StepExecutionResult> matchesOutput(Step step, Path sandboxDirectory) {
		return new TypeSafeDiagnosingMatcher<StepExecutionResult>() {
			@Override
			protected boolean matchesSafely(StepExecutionResult stepResult, Description description) {
				String actualOutput = stepResult.getOutput().map(String::trim).filter(it -> !it.isEmpty()).orElse(null);
				String expectedOutput = step.getOutput().map(String::trim).filter(it -> !it.isEmpty()).orElse(null);
				if (actualOutput == null && expectedOutput != null) {
					description.appendText("no output produced when expecting output");
					return false;
				} else if (actualOutput != null && expectedOutput == null) {
					return true; // no output expected, for now lets not assert it
				} else if (actualOutput == null && expectedOutput == null) {
					return true; // nothing to assert
				}

				if (expectedOutput.contains("[...]")) {
//					for (String s : expectedOutput.split("\\[...]")) {
//						actualOutput
//					}
					return true; // for now
				}

				try {
					if (step.getExecutable().equals("./gradlew")) {
						String acOut = BuildResult.from(actualOutput).asRichOutputResult().toString().trim();
						if (acOut.startsWith(expectedOutput)) {
							return true;
						}

						BuildResult expectedResult = BuildResult.from(expectedOutput);
						// TODO: Step result should have the exact information of the execution and sandbox
						BuildResult actualResult = BuildResult.from(actualOutput.replace(sandboxDirectory.toFile().getCanonicalPath(), "."));
						try {
							for (BuildTask expectedTask : expectedResult.getTasks()) {
								BuildTask actualTask = actualResult.task(expectedTask.getPath());
								if (actualTask == null) throw new Exception(); // failure
								if (!actualTask.getOutcome().equals(expectedTask.getOutcome()))
									throw new Exception(); // failure

								List<String> actualLines = actualTask.getOutput().lines().toList();
								for (String expectedLine : expectedTask.getOutput().lines().toList()) {
									int i = actualLines.indexOf(expectedLine);
									if (i == -1) throw new Exception(); // failure
//									actualLines = actualLines.subList(i + 1, actualLines.size());
								}
							}
							return true;
						} catch (Exception e) {
							description.appendText("received output\n" + acOut);
							return false;
						}
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				if (expectedOutput.equals(actualOutput)) {
					return true;
				} else {
					description.appendText("received output\n" + actualOutput);
					return false;
				}
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("expected output\n" + step.getOutput().map(String::trim).orElse("<null>"));
			}
		};
	}
}
