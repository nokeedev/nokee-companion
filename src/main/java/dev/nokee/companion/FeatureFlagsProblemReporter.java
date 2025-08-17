package dev.nokee.companion;

import org.gradle.api.Project;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

abstract class FeatureFlagsProblemReporter {
	public static FeatureFlagsProblemReporter forProject(Project project) {
		return project.getObjects().newInstance(FeatureFlagsProblemReporter.class);
	}

	private final ProviderFactory providers;

	@Inject
	public FeatureFlagsProblemReporter(ProviderFactory providers) {
		this.providers = providers;
	}

	public void report(Set<String> knownFeatureNames) {
		Set<String> allRequestedFeatures = new LinkedHashSet<>();
		allRequestedFeatures.addAll(providers.gradlePropertiesPrefixedBy("dev.nokee.native-companion.").map(this::onlyFeatureFlags).get());
		allRequestedFeatures.removeAll(knownFeatureNames);
		if (!allRequestedFeatures.isEmpty()) {
			throw new RuntimeException("The following features are not known by the native-companion plugin: " + String.join(", ", allRequestedFeatures) + "\n\tSee https://github.com/nokeedev/nokee-companion/blob/main/native-companion-plugin.md#features for more information");
		}

		if (!providers.systemPropertiesPrefixedBy("dev.nokee.native-companion.").map(this::onlyFeatureFlags).get().isEmpty()) {
			throw new RuntimeException("Please use Gradle properties to enable Native Companion features!");
		};
	}

	private Collection<String> onlyFeatureFlags(Map<String, String> properties) {
		return properties.keySet().stream().filter(it -> it.endsWith(".enabled")).map(it -> {
			it = it.substring("dev.nokee.native-companion.".length());
			it = it.substring(0, it.length() - ".enabled".length());
			return it;
		}).filter(it -> !it.equals("all-features")).collect(Collectors.toList());
	}
}
