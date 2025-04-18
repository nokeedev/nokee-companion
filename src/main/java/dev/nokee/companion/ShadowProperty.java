package dev.nokee.companion;

import org.gradle.api.Transformer;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Provider;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Represents an extra property lives in the shadow of read-only getter.
 *
 * @param <T> the property type
 */
public abstract class ShadowProperty<T> {
	private final Object self;
	private final String propertyName;
	private final Supplier<T> getter;

	private ShadowProperty(Object self, String propertyName, Supplier<T> getter) {
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
			@Nullable final Object value = ext.get(propertyName);
			if (value != null) {
				@SuppressWarnings("unchecked")
				final T result = (T) unpack(value);
				return result;
			}
			// fall through to getter value
		}
		return getter.get();
	}

	private Object unpack(@Nullable Object obj) {
		assert obj != null; // assumption from usage
		if (obj instanceof Provider) {
			return ((Provider<?>) obj).get();
		}
		return obj;
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

	public ShadowProperty<T> mut(Transformer<T, T> newValueTransformer) {
		set(newValueTransformer.transform(get()));
		return this;
	}

	/**
	 * Replace the property value.
	 * <b>Note:</b> the new value will live in the shadow of the original value, make sure everyone knows.
	 *
	 * @param providedValue  the new deferred value, must be of the same type as the original value
	 */
	public void set(Provider<? extends T> providedValue) {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		ext.set(propertyName, providedValue);
	}

	/**
	 * Mutate the current property value.
	 * <b>Note:</b> the new value will live in the shadow of the original value, make sure everyone knows.
	 *
	 * @param newValueTransformer  the transformer of the current value into the new value
	 * @return the current shadow property
	 */
	public ShadowProperty<T> mut(Transformer<T, T> newValueTransformer) {
		Object value = rawValue();
		if (value instanceof Provider) {
			@SuppressWarnings("unchecked")
			final Provider<T> provider = (Provider<T>) value;
			set(provider.map(newValueTransformer));
		} else {
			@SuppressWarnings("unchecked")
			final T previousValue = (T) value;
			set(Objects.requireNonNull(newValueTransformer.transform(previousValue), "transformed value must not be null"));
		}
		return this;
	}

	protected Object rawValue() {
		final ExtraPropertiesExtension ext = ((ExtensionAware) self).getExtensions().getExtraProperties();
		if (ext.has(propertyName)) {
			@Nullable final Object value = ext.get(propertyName);
			if (value != null) {
				return value;
			}
			// fall through to getter value
		}
		return getter.get();
	}


	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return "shadow property '" + propertyName + "' on " + self;
	}

	/**
	 * Constructs a shadow property.
	 *
	 * @param self  the target object
	 * @param propertyName  the property name (in terms of Groovy/Kotlin)
	 * @param getter  the plain object getter to the read-only value
	 */
	public static <T> ShadowProperty<T> of(Object self, String propertyName, Supplier<T> getter) {
		return new Impl<>(self, propertyName, getter);
	}

	private static final class Impl<T> extends ShadowProperty<T> implements Callable<Object> {
		private Impl(Object self, String propertyName, Supplier<T> getter) {
			super(self, propertyName, getter);
		}

		/**
		 * Convenience to use in {@code project.files(shadowProperty)} (for provider, use {@code project.provider(shadowProperty::get)}).
		 * In both cases, the value will be deferred.
		 * <b>Note:</b> Must NOT be used during self-assign.
		 *
		 * @return the property value
		 * @throws Exception if {@link #get()} fails
		 */
		@Override
		public Object call() throws Exception {
			return rawValue();
		}
	}
}
