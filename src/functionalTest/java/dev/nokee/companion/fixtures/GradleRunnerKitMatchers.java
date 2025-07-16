package dev.nokee.companion.fixtures;

import dev.gradleplugins.runnerkit.BuildTask;
import org.hamcrest.Description;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import static org.hamcrest.Matchers.containsString;

public class GradleRunnerKitMatchers {
	public static Matcher<BuildTask> performFullRebuildForIncrementalTask() {
		return new TypeSafeDiagnosingMatcher<>() {
			@Override
			protected boolean matchesSafely(BuildTask buildTask, Description description) {
				return buildTask.getOutput().contains(String.format("The input changes require a full rebuild for incremental task '%s'.", buildTask.getPath()));
			}

			@Override
			public void describeTo(Description description) {

			}
		};
	}
}
