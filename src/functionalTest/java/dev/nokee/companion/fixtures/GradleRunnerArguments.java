package dev.nokee.companion.fixtures;

import dev.gradleplugins.runnerkit.GradleRunner;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.UnaryOperator;

public class GradleRunnerArguments implements Iterable<String> {
	private final List<String> tasks;
	private final File gradleUserHomeDirectory;
	private final List<String> additionalArgs;
	private final DeprecationChecks deprecationChecks;
	private final WelcomeMessage welcomeMessage;
	private final BuildCache buildCache;

	private GradleRunnerArguments(List<String> tasks, File gradleUserHomeDirectory, List<String> additionalArgs, DeprecationChecks deprecationChecks, WelcomeMessage welcomeMessage, BuildCache buildCache, Stacktrace stacktrace, Logging logging, ConfigurationCache configurationCache) {
		this.tasks = tasks;
		this.gradleUserHomeDirectory = gradleUserHomeDirectory;
		this.additionalArgs = additionalArgs;
		this.deprecationChecks = deprecationChecks;
		this.welcomeMessage = welcomeMessage;
		this.buildCache = buildCache;
		this.stacktrace = stacktrace;
		this.logging = logging;
		this.configurationCache = configurationCache;
	}

	public static GradleRunnerArguments create() {
		return new GradleRunnerArguments(Collections.emptyList(), null, Collections.emptyList(), DeprecationChecks.FAILS, WelcomeMessage.DISABLED, BuildCache.DISABLED, Stacktrace.SHOW, Logging.LIFECYCLE, null);
	}

	//region Flag `--info` configuration
	private final Logging logging;

	public GradleRunnerArguments withInfoLogging() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace, Logging.INFO, configurationCache);
	}

	public GradleRunnerArguments withQuietLogging() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace, Logging.QUIET, configurationCache);
	}

	private enum Logging { LIFECYCLE, INFO, QUIET }
	//endregion

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
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace, logging, configurationCache);
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
		try {
			// TODO: Maybe just on Windows? See https://github.com/gradle/gradle/issues/12535
			return withGradleUserHomeDirectory(Files.createTempDirectory("user-home").toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	//endregion

	public GradleRunnerArguments withTasks(Object... tasks) {
		return new GradleRunnerArguments(Arrays.stream(tasks).map(Object::toString).toList(), gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace, logging, configurationCache);
	}

	public GradleRunnerArguments append(String arg) {
		List<String> additionalArgs = new ArrayList<>(this.additionalArgs);
		additionalArgs.add(arg);
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, stacktrace, logging, configurationCache);
	}

	//region
	private final Stacktrace stacktrace;

	public GradleRunnerArguments withStacktraceDisabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, buildCache, Stacktrace.HIDE, logging, configurationCache);
	}

	private enum Stacktrace {
		SHOW, HIDE
	}
	//endregion

	//region
	public GradleRunnerArguments withoutDeprecationChecks() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, null, welcomeMessage, buildCache, stacktrace, logging, configurationCache);
	}

	private enum DeprecationChecks {
		FAILS
	}
	//endregion

	//region
	public GradleRunnerArguments withWelcomeMessageEnabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, WelcomeMessage.ENABLED, buildCache, stacktrace, logging, configurationCache);
	}

	// See org.gradle.launcher.cli.DefaultCommandLineActionFactory#WELCOME_MESSAGE_ENABLED_SYSTEM_PROPERTY
	private static final String WELCOME_MESSAGE_ENABLED_SYSTEM_PROPERTY = "org.gradle.internal.launcher.welcomeMessageEnabled";

	private enum WelcomeMessage {
		ENABLED, DISABLED
	}
	//endregion

	//region Flag --configuration-cache configuration
	private final ConfigurationCache configurationCache;

	public GradleRunnerArguments withConfigurationCacheEnabled() {
		return withConfigurationCacheEnabled(UnaryOperator.identity());
	}

	public GradleRunnerArguments withConfigurationCacheEnabled(UnaryOperator<ConfigurationCacheProblems> action) {
		ConfigurationCacheProblems problems = action.apply(ConfigurationCacheProblems.FAIL);
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, new ArrayList<>() {{ addAll(additionalArgs); add("--configuration-cache-problems=" + problems.flagValue()); }}, deprecationChecks, welcomeMessage, buildCache, stacktrace, logging, ConfigurationCache.Enabled);
	}

	public enum ConfigurationCacheProblems {
		FAIL {
			String flagValue() {
				return "fail";
			}
		}, WARN {
			String flagValue() {
				return "warn";
			}
		};

		abstract String flagValue();

		public ConfigurationCacheProblems withoutProblemsChecks() {
			return WARN;
		}
	}

	private enum ConfigurationCache {
		Enabled, Disabled
	}
	//endregion

	//region
	public GradleRunnerArguments withBuildCacheEnabled() {
		return new GradleRunnerArguments(tasks, gradleUserHomeDirectory, additionalArgs, deprecationChecks, welcomeMessage, BuildCache.ENABLED, stacktrace, logging, configurationCache);
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
		if (logging == Logging.INFO) result.add("--info");
		if (logging == Logging.QUIET) result.add("--quiet");

		if (configurationCache == ConfigurationCache.Enabled) result.add("--configuration-cache");
		if (configurationCache == ConfigurationCache.Disabled) result.add("--no-configuration-cache");

		result.addAll(tasks);
		return result;
	}

	@Override
	public Iterator<String> iterator() {
		return toList().iterator();
	}
}
