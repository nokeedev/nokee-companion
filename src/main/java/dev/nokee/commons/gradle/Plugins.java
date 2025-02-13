package dev.nokee.commons.gradle;

import groovy.lang.Closure;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.PluginAware;

import javax.inject.Inject;
import java.util.Map;

// https://github.com/gradle/gradle/issues/32438
public class Plugins<T extends PluginAware> {
	private final T target;

	/**
	 * Constructs this plugins instance for the specified target.
	 *
	 * @param target  the target
	 */
	@Inject
	public Plugins(T target) {
		this.target = target;
	}

	/**
	 * Applies the specified plugin by type to the plugins' owner.
	 * Essentially equivalent to {@link org.gradle.api.plugins.PluginManager#apply(Class)} or {@link PluginAware#apply(Map)}.
	 *
	 * @param pluginType  the plugin type to apply
	 */
	public void apply(Class<? extends Plugin<T>> pluginType) {
		target.getPluginManager().apply(pluginType);
	}

	/**
	 * Applies the specified plugin by id to the plugins' owner.
	 * Essentially equivalent to {@link org.gradle.api.plugins.PluginManager#apply(String)} or {@link PluginAware#apply(Map)}.
	 *
	 * @param pluginId  the plugin id to apply
	 */
	public void apply(String pluginId) {
		target.getPluginManager().apply(pluginId);
	}

	/**
	 * Registers a callback when the Gradle finish applying the specified plugin type.
	 *
	 * @param pluginType  the plugin type to watch
	 * @param callback  the plugin listener to call back
	 */
	public void whenPluginApplied(Class<? extends Plugin<T>> pluginType, Runnable callback) {
		target.getPlugins().withType(pluginType, __ -> callback.run());
	}

	/**
	 * Registers a callback when the Gradle finish applying the specified plugin id.
	 *
	 * @param pluginId  the plugin id to watch
	 * @param callback  the plugin listener to call back
	 */
	public void whenPluginApplied(String pluginId, Runnable callback) {
		target.getPlugins().withId(pluginId, __ -> callback.run());
	}

	/**
	 * Returns the plugins for the specified project.
	 *
	 * @param project  the project
	 * @return the plugins of the specified project
	 */
	public static Plugins<Project> forProject(Project project) {
		return new Plugins<>(project);
	}

	/**
	 * Returns the plugins for the specified settings.
	 *
	 * @param settings  the settings
	 * @return the plugins of the specified settings
	 */
	public static Plugins<Settings> forSettings(Settings settings) {
		return new Plugins<>(settings);
	}
}
