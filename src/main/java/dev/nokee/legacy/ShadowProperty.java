package dev.nokee.legacy;

import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

// Maybe move this to commons
public final class ShadowProperty<T> implements Callable<T> {
	private final Object self;
	private final String propertyName;
	private final Supplier<T> getter;

	public ShadowProperty(Object self, String propertyName, Supplier<T> getter) {
		this.self = self;
		this.propertyName = propertyName;
		this.getter = getter;
	}

	public T get() {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		if (ext.has(propertyName)) {
			@SuppressWarnings("unchecked")
			T result = (T) ext.get(propertyName);
			return result;
		}
		return getter.get();
	}

	public void set(T newValue) {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		ext.set(propertyName, newValue);
	}

	@Override
	public T call() throws Exception {
		return get();
	}

	@Override
	public String toString() {
		return "shadow property '" + propertyName + "' on " + self;
	}
}
