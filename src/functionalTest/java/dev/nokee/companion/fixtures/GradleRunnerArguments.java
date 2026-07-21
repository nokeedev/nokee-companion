package dev.nokee.companion.fixtures;

import dev.gradleplugins.runnerkit.GradleRunner;

import java.io.File;
import java.util.*;

public class GradleRunnerArguments implements Iterable<String> {
	private final List<String> tasks;
	private final File gradleUserHomeDirectory;
	private final List<String> additionalArgs;
	private final DeprecationChecks deprecationChecks;
	private final WelcomeMessage welcomeMessage;
	private final BuildCache buildCache;

	private GradleRunnerArguments(List<String> tasks, File gradleUserHomeDirectory, List<String> additionalArgs, DeprecationChecks deprecationChecks, WelcomeMessage welcomeMessage, BuildCache buildCache, Stacktrace stacktrace) {
		this.tasks = tasks;
		this.gradleUserHomeDirectory = gradleUserHomeDirectory;
		this.additionalArgs = additionalArgs;
		this.deprecationChecks = deprecationChecks;
		this.welcomeMessage = welcomeMessage;
		this.buildCache = buildCache;
		this.stacktrace = stacktrace;
	}

	public static GradleRunnerArguments create() {
		return new GradleRunnerArguments(Collections.emptyList(), null, Collections.emptyList(), DeprecationChecks.FAILS, WelcomeMessage.DISABLED, BuildCache.DISABLED, Stacktrace.SHOW);
	}

	//region Flag `--gradle-user-home` configuration
	/**
	 * Sets the <em>Gradle</em> user home dir.
	 * Setting to null requests that the executer use the real default Gradle user home dir rather than the default used for testing.
	 *
	 * <p>Note: does not affect the daemon base dir.</p>
	 *
	 * @param gradleUserHomeDirectory  the Gradle user home directory to use
	 * @return a new {@link GradleRunnerArguments} instance configured with the specified Gradle user home directory, never null.
	 */
	public GradleRunnerArguments withGradleUserHomeDirectory(File gradleUserHomeDirectory) {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace);
	}

	/**
	 * Configures a unique Gradle user home directory for the test.
	 *
	 * <p>The Gradle user home directory used will be underneath the working directory.
	 *
	 * <p>Note: does not affect the daemon base dir.</p>
	 *
	 * @return a new {@link GradleRunner} instance configured with a unique Gradle user home directory, neverl null.
	 */
	public GradleRunnerArguments requireOwnGradleUserHomeDirectory(String because) {
		return withGradleUserHomeDirectory(new File("user-home"));
	}
	//endregion

	public GradleRunnerArguments withTasks(Object... tasks) {
		return new GradleRunnerArguments(Arrays.stream(tasks).map(Object::toString).toList(), gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace);
	}

	public GradleRunnerArguments append(String arg) {
		List<String> additionalArgs = new ArrayList<>(this.additionalArgs);
		additionalArgs.add(arg);
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace);
	}

	//region
	private final Stacktrace stacktrace;

	public GradleRunnerArguments withStacktraceDisabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, Stacktrace.HIDE);
	}

	private enum Stacktrace {
		SHOW, HIDE
	}
	//endregion

	//region
	public GradleRunnerArguments withoutDeprecationChecks() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, null, welcomeMessage, buildCache, stacktrace);
	}

	private enum DeprecationChecks {
		FAILS
	}
	//endregion

	//region
	public GradleRunnerArguments withWelcomeMessageEnabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, WelcomeMessage.ENABLED, buildCache, stacktrace);
	}

	// See org.gradle.launcher.cli.DefaultCommandLineActionFactory#WELCOME_MESSAGE_ENABLED_SYSTEM_PROPERTY
	private static final String WELCOME_MESSAGE_ENABLED_SYSTEM_PROPERTY = "org.gradle.internal.launcher.welcomeMessageEnabled";

	private enum WelcomeMessage {
		ENABLED, DISABLED
	}
	//endregion

	//region
	public GradleRunnerArguments withBuildCacheEnabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, BuildCache.ENABLED, stacktrace);
	}

	private enum BuildCache {
		ENABLED, DISABLED
	}
	//endregion

	public List<String> toList() {
		List<String> result = new ArrayList<>();
		result.addAll(additionalArgs);
		if (buildCache == BuildCache.ENABLED) result.add("--build-cache");
		if (stacktrace == Stacktrace.SHOW) result.add("--stacktrace");
		if (gradleUserHomeDirectory != null) {
			result.add("--gradle-user-home");
			result.add(gradleUserHomeDirectory.getPath());
		}
		if (deprecationChecks == DeprecationChecks.FAILS) result.add("--warning-mode=fail");
		if (welcomeMessage != null) result.add("-D" + WELCOME_MESSAGE_ENABLED_SYSTEM_PROPERTY + "=" + (welcomeMessage == WelcomeMessage.ENABLED));

		result.addAll(tasks);
		return result;
	}

	@Override
	public Iterator<String> iterator() {
		return toList().iterator();
	}
}
