package dev.nokee.companion;

import org.gradle.api.Describable;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

class CppDisplayNames {
	public static Describable describe(CppComponent component) {
		return () -> {
			if (component instanceof CppApplication) {
				return "C++ application '" + component.getName() + "'";
			} else if (component instanceof CppLibrary) {
				return "C++ library '" + component.getName() + "'";
			} else if (component instanceof CppTestSuite) {
				return "C++ test suite '" + component.getName() + "'";
			} else {
				return "C++ component '" + component.getName() + "'";
			}
		};
	}
}
