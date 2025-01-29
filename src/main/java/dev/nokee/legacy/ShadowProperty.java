package dev.nokee.legacy;

import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Represents an extra property lives in the shadow of read-only getter.
 *
 * @param <T> the property type
 */
public final class ShadowProperty<T> implements Callable<T> {
	private final Object self;
	private final String propertyName;
	private final Supplier<T> getter;

	/**
	 * Constructs a shadow property.
	 *
	 * @param self  the target object
	 * @param propertyName  the property name (in terms of Groovy/Kotlin)
	 * @param getter  the plain object getter to the read-only value
	 */
	public ShadowProperty(Object self, String propertyName, Supplier<T> getter) {
		this.self = self;
		this.propertyName = propertyName;
		this.getter = getter;
	}

	/**
	 * Returns the property's value.
	 * The shadow (extra properties) are checked first, else it defaults to the plain getter.
	 *
	 * @return  the property value
	 */
	public T get() {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		if (ext.has(propertyName)) {
			@SuppressWarnings("unchecked")
			final T result = (T) ext.get(propertyName);
			return result;
		}
		return getter.get();
	}

	/**
	 * Replace the property value.
	 * <b>Note:</b> the new value will live in the shadow of the original value, make sure everyone knows.
	 *
	 * @param newValue  the new value, must be of the same type as the original value
	 */
	public void set(T newValue) {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		ext.set(propertyName, newValue);
	}

	/**
	 * Convenience to use in {@code project.files(shadowProperty)} or in {@code project.provider(shadowProperty)}.
	 * In both cases, the value will be deferred.
	 * <b>Note:</b> Must NOT be used during self-assign.
	 *
	 * @return the property value
	 * @throws Exception if {@link #get()} fails
	 */
	@Override
	public T call() throws Exception {
		return get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return "shadow property '" + propertyName + "' on " + self;
	}
}
