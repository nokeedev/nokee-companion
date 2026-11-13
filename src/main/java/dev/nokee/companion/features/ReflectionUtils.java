package dev.nokee.companion.features;

import javax.annotation.Nullable;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class ReflectionUtils {
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

	public static @Nullable Field findField(Class<?> clazz, String fieldName) {
		for (Class<?> type = clazz; type != null; type = type.getSuperclass()) {
			try {
				return type.getDeclaredField(fieldName);
			} catch (NoSuchFieldException e) {
			}
		}
		return null;
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

	public static void updateFieldValue(Field field, Object instance, Object value) {
		try {
			removeFinal(makeAccessible(field)).set(instance, value);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public static Field removeFinal(Field field) {
		if (isFinal(field)) {
			try {
				Field modifiers = Field.class.getDeclaredField("modifiers");
				makeAccessible(modifiers);
				modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
			} catch (NoSuchFieldException e) {
				// ignore, may be on JDK 12+
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		return field;
	}

	public static boolean isFinal(Field field) {
		return Modifier.isFinal(field.getModifiers());
	}
}
