package dev.nokee.companion;

import org.gradle.api.Project;

/**
 * Represent all configurations supported by the {@literal dev.nokee.native-companion} plugin.
 */
public interface NativeCompanionExtension {
	/**
	 * Enables the specified feature.
	 *
	 * @param featureName  the feature name, see documentation
	 */
	void enableFeaturePreview(String featureName);

	/**
	 * Returns the native companion extension of the specified project.
	 *
	 * @param project  the project to get the extension from
	 * @return the native companion extension
	 */
	static NativeCompanionExtension nativeCompanionOf(Project project) {
		return project.getExtensions().getByType(NativeCompanionExtension.class);
	}
}
