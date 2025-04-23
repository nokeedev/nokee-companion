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
}
