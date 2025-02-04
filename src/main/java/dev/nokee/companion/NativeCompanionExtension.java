package dev.nokee.companion;

import org.gradle.api.Project;

import javax.inject.Inject;

public class NativeCompanionExtension {
	private final Project project;

	@Inject
	public NativeCompanionExtension(Project project) {
		this.project = project;
	}

	public void enableFeaturePreview(String featureName) {
		project.getPluginManager().apply("native-companion.features." + featureName);
	}
}
