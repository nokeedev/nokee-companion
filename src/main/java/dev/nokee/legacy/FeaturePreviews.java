package dev.nokee.legacy;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;

abstract /*final*/ class FeaturePreviews {
	private final Project project;
	private final ProviderFactory providers;

	@Inject
	public FeaturePreviews(Project project, ProviderFactory providers) {
		this.project = project;
		this.providers = providers;
	}

	public Feature named(String featureName) {
		return new Feature() {
			@Override
			public void ifEnabled(Action<? super Project> action) {
				boolean enabled = providers.gradleProperty("dev.nokee.native-companion." + featureName + ".enabled")
					.orElse(providers.gradleProperty("dev.nokee.native-companion.all-features.enabled"))
					.map(Boolean::valueOf).getOrElse(false);
				if (enabled) {
					action.execute(project);
				}
			}
		};
	}

	public interface Feature {
		void ifEnabled(Action<? super Project> action);
	}

	public static abstract class Plugin implements org.gradle.api.Plugin<Project> {
		private final String featureName;

		@Inject
		public Plugin(String featureName) {
			this.featureName = featureName;
		}

		@Override
		public void apply(Project project) {
			featurePreviews(project).named(featureName).ifEnabled(this::doApply);
		}

		protected abstract void doApply(Project project);
	}

	public static FeaturePreviews featurePreviews(Project project) {
		return project.getObjects().newInstance(FeaturePreviews.class, project);
	}
}
