package dev.nokee.companion;

import dev.gradleplugins.exemplarkit.*;
import dev.gradleplugins.runnerkit.BuildResult;
import dev.gradleplugins.runnerkit.GradleRunner;
import dev.gradleplugins.runnerkit.TaskOutcome;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.nokee.companion.ExemplarMatchers.matchesOutput;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ExemplarTestUtils {
	public static List<DynamicTest> getDynamicTests(ExemplarElement<?> project, Path testDirectory, GradleRunner runner) {
		Exemplar.Builder builder = Exemplar.builder();
		for (Step step : project.getSteps()) {
			builder.step(step);
		}
		ExemplarExecutor executor = ExemplarExecutor.builder().registerCommandLineToolExecutor(new StepExecutor() {
			@Override
			public boolean canHandle(Step step) {
				return step.getExecutable().equals("./gradlew");
			}

			@Override
			public StepExecutionResult run(StepExecutionContext context) {
				BuildResult result = null;
				if (context.getCurrentStep().getOutput().orElse("").contains("FAILED")) {
					result = runner.withArguments(context.getCurrentStep().getArguments()).buildAndFail();
				} else {
					result = runner.withArguments(context.getCurrentStep().getArguments()).build();
				}
				return StepExecutionResult.stepExecuted(result.getTasks().stream().anyMatch(it -> it.getOutcome().equals(TaskOutcome.FAILED)) ? -1 : 0, result.getOutput());
			}
		}).build();
		Exemplar exemplar = builder.fromDirectory(testDirectory.toFile()).build();
		ExemplarExecutionResult result = ExemplarRunner.create(executor).inDirectory(testDirectory.toFile()).using(exemplar).run();

		List<DynamicTest> tests = new ArrayList<>();
		for (int i = 0; i < result.getStepResults().size(); i++) {
			StepExecutionResult stepResult = result.getStepResults().get(i);
			Step step = exemplar.getSteps().get(i);

			tests.add(DynamicTest.dynamicTest("STEP: " + step.getExecutable() + " " + String.join(" ", step.getArguments()), new File("/Users/daniel/Projects/nokeedev/nokee-legacy/samples/cpp-compile-detects-source-file-relocation/README.md").toURI(), new Executable() {
				@Override
				public void execute() throws Throwable {
					assertThat("step executed successfully", stepResult.getOutcome(), equalTo(StepExecutionOutcome.EXECUTED));
					assertThat(stepResult, matchesOutput(step, testDirectory));
				}
			}));
		}
		return tests;
	}
}
