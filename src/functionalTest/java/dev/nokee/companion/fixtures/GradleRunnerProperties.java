package dev.nokee.companion.fixtures;

import java.util.Map;

public final class GradleRunnerProperties {
	public static Map<String, Object> forConfigurationCacheEnabled() {
		return Map.of("org.gradle.configuration-cache", true);
	}
}
