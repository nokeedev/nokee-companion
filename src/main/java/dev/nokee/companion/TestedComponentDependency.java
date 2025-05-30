package dev.nokee.companion;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ProviderConvertible;
import org.gradle.language.ComponentDependencies;

/**
 * Represent a tested component dependency where user can select the test elements to integrate with.
 */
public interface TestedComponentDependency extends ProviderConvertible<ModuleDependency> {
	/**
	 * {@return a dependency that integrate with the testable objects and headers of the tested component}
	 */
	ModuleDependency asObjects();

	/**
	 * {@return a dependency that integrate with the testable sources and headers of the tested component}
	 */
	ModuleDependency asSources();

	/**
	 * {@return a dependency that integrate with the product and testable headers of the tested component}
	 */
	ModuleDependency asProduct();

	/**
	 * Represent the dependency modifier for the tested component.
	 * The companion plugin register the modifier on {@code Project#dependencies} and {@code CppTestSuite#dependencies}.
	 */
	interface Modifier {
		/**
		 * Modify a project dependency.
		 *
		 * @param dependency  the project dependency to modify
		 * @return a tested component dependency that can be further modified
		 */
		TestedComponentDependency modify(ProjectDependency dependency);

		/**
		 * Modify a project dependency.
		 *
		 * @param project  the project dependency to modify
		 * @return a tested component dependency that can be further modified
		 */
		TestedComponentDependency modify(Project project);

		/**
		 * Retries this modifier for {@code Project#dependencies}.
		 * @param dependencies  the project dependencies handler
		 * @return the modifier
		 */
		static Modifier forDependencies(DependencyHandler dependencies) {
			return (Modifier) dependencies.getExtensions().getByName("testedComponent");
		}

		/**
		 * Retries this modifier for {@code CppUnitTest#dependencies}.
		 * @param dependencies  the C++ unit test dependencies handler
		 * @return the modifier
		 */
		static Modifier forDependencies(ComponentDependencies dependencies) {
			return (Modifier) ((ExtensionAware) dependencies).getExtensions().getByName("testedComponent");
		}
	}
}
