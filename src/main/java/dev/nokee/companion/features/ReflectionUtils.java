package dev.nokee.companion.features;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;

final class ReflectionUtils {
	private ReflectionUtils() {}

	public static <T extends AccessibleObject> T makeAccessible(T object) {
		if (!object.isAccessible()) {
			object.setAccessible(true);
		}

		return object;
	}

	public static Field getField(Class<?> clazz, String fieldName) {
		try {
			return clazz.getDeclaredField(fieldName);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	public static <T, R> R readFieldValue(Class<T> clazz, String fieldName, T instance) {
		return readFieldValue(getField(clazz, fieldName), instance);
	}

	public static <R> R readFieldValue(Field field, Object instance) {
		try {
			@SuppressWarnings("unchecked")
			R result = (R) makeAccessible(field).get(instance);
			return result;
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
}
