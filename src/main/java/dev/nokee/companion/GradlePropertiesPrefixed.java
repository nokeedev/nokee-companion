package dev.nokee.companion;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;

// TODO: Move to backport
abstract class GradlePropertiesPrefixed {
	public static GradlePropertiesPrefixed forProject(Project project) {
		if (GradleVersion.current().compareTo(GradleVersion.version("8.0")) >= 0) {
			return project.getObjects().newInstance(ProviderFactoryBackedImpl.class);
		} else {
			return project.getObjects().newInstance(ProjectBackedImpl.class);
		}
	}

	public abstract Provider<Map<String, String>> by(String variableNamePrefix);

	/*private*/ static abstract class ProviderFactoryBackedImpl extends GradlePropertiesPrefixed {
		private final ProviderFactory providers;

		@Inject
		public ProviderFactoryBackedImpl(ProviderFactory providers) {
			this.providers = providers;
		}

		public Provider<Map<String, String>> by(String variableNamePrefix) {
			return providers.gradlePropertiesPrefixedBy(variableNamePrefix);
		}
	}

	/*private*/ static abstract class ProjectBackedImpl extends GradlePropertiesPrefixed {
		private final Project project;
		private final ProviderFactory providers;

		@Inject
		public ProjectBackedImpl(Project project, ProviderFactory providers) {
			this.project = project;
			this.providers = providers;
		}

		@Override
		public Provider<Map<String, String>> by(String variableNamePrefix) {
			return providers.provider(() -> {
				return project.getProperties().entrySet().stream().filter(it -> it.getKey().startsWith(variableNamePrefix)).collect(Collectors.toMap(Map.Entry::getKey, it -> it.getValue().toString()));
			});
		}
	}
}
