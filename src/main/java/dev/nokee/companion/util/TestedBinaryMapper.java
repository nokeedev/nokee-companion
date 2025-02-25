package dev.nokee.companion.util;

import org.gradle.api.Transformer;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.CppStaticLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;

import java.util.Set;

/**
 * Convenient transformer to map a production C++ component to a tested binary.
 */
public abstract class TestedBinaryMapper implements Transformer<CppBinary, ProductionCppComponent> {
	private final CppTestExecutable testExecutable;

	protected TestedBinaryMapper(CppTestExecutable testExecutable) {
		this.testExecutable = testExecutable;
	}

	@Override
	public final CppBinary transform(ProductionCppComponent mainComponent) {
		Set<? extends CppBinary> candidates = mainComponent.getBinaries().get();
		for (CppBinary testedBinary : candidates) {
			if (isTestedBinary(testExecutable, mainComponent, testedBinary)) {
				return testedBinary;
			}
		}
		return null;
	}

	protected abstract boolean isTestedBinary(CppTestExecutable testExecutable, ProductionCppComponent mainComponent, CppBinary testedBinary);

	// From Gradle code
	@SuppressWarnings("UnstableApiUsage")
	protected final boolean hasDevelopmentBinaryLinkage(ProductionCppComponent mainComponent, CppBinary testedBinary) {
		if (!(testedBinary instanceof ComponentWithLinkUsage)) {
			return true;
		}
		ComponentWithLinkUsage developmentBinaryWithUsage = (ComponentWithLinkUsage) mainComponent.getDevelopmentBinary().get();
		ComponentWithLinkUsage testedBinaryWithUsage = (ComponentWithLinkUsage) testedBinary;
		if (testedBinaryWithUsage instanceof CppSharedLibrary && developmentBinaryWithUsage instanceof CppSharedLibrary) {
			return true;
		} else if (testedBinaryWithUsage instanceof CppStaticLibrary && developmentBinaryWithUsage instanceof CppStaticLibrary) {
			return true;
		} else {
			return false;
		}
	}
}
