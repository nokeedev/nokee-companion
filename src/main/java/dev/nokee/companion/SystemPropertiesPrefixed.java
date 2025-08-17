package dev.nokee.companion;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;

// TODO: Move to backport
abstract class SystemPropertiesPrefixed {
	public static SystemPropertiesPrefixed forProject(Project project) {
		if (GradleVersion.current().compareTo(GradleVersion.version("8.0")) >= 0) {
			return project.getObjects().newInstance(ProviderFactoryBackedImpl.class);
		} else {
			return project.getObjects().newInstance(ProjectBackedImpl.class);
		}
	}

	public abstract Provider<Map<String, String>> by(String variableNamePrefix);

	/*private*/ static abstract class ProviderFactoryBackedImpl extends SystemPropertiesPrefixed {
		private final ProviderFactory providers;

		@Inject
		public ProviderFactoryBackedImpl(ProviderFactory providers) {
			this.providers = providers;
		}

		public Provider<Map<String, String>> by(String variableNamePrefix) {
			return providers.systemPropertiesPrefixedBy(variableNamePrefix);
		}
	}

	/*private*/ static abstract class ProjectBackedImpl extends SystemPropertiesPrefixed {
		private final ProviderFactory providers;

		@Inject
		public ProjectBackedImpl(ProviderFactory providers) {
			this.providers = providers;
		}

		@Override
		public Provider<Map<String, String>> by(String variableNamePrefix) {
			return providers.provider(() -> {
				return System.getProperties().entrySet().stream().filter(it -> it.getKey().toString().startsWith(variableNamePrefix)).collect(Collectors.toMap(it -> it.getKey().toString(), it -> it.getValue().toString()));
			});
		}
	}
}
