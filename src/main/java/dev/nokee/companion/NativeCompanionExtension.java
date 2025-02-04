package dev.nokee.companion;

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
}
