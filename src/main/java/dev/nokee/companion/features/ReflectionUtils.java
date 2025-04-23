package dev.nokee.companion.features;

import java.lang.reflect.AccessibleObject;

final class ReflectionUtils {
	private ReflectionUtils() {}

	public static <T extends AccessibleObject> T makeAccessible(T object) {
		if (!object.isAccessible()) {
			object.setAccessible(true);
		}

		return object;
	}
}
