package dev.nokee.companion.features;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.*;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static dev.nokee.commons.hamcrest.gradle.NamedMatcher.named;
import static dev.nokee.commons.hamcrest.gradle.provider.NoValueProviderMatcher.noValueProvider;
import static dev.nokee.commons.hamcrest.gradle.provider.ProviderOfMatcher.providerOf;
import static dev.nokee.companion.CppUnitTestExtensions.testedBinaryOf;
import static dev.nokee.companion.CppUnitTestExtensions.testedComponentOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.isA;

class CppUnitTestExtensionsIntegrationTests {
	Project project;
	@TempDir Path testDirectory;
	CppTestSuite testSuite;

	@BeforeEach
	void setup() {
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPlugins().apply("dev.nokee.native-companion");
		project.getPlugins().apply("cpp-unit-test");
		testSuite = project.getExtensions().getByType(CppTestSuite.class);
	}

	@Test
	void hasNoTestedComponent() {
		assertThat(testedComponentOf(testSuite), noValueProvider());
	}

	@Test
	void hasNoTestedBinary() {
		((ProjectInternal) project).evaluate();
		CppTestExecutable testExecutable = testSuite.getBinaries().get().stream().map(CppTestExecutable.class::cast).findFirst().orElseThrow();
		assertThat(testedBinaryOf(testExecutable), noValueProvider());
	}

	@Nested
	class WhenCppApplicationPluginApplied {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-application");
		}

		@Test
		void useCppApplicationAsTestedComponent() {
			assertThat(testedComponentOf(testSuite), providerOf(allOf(named("main"), isA(CppApplication.class))));
		}

		@Test
		void useDebugApplicationAsTestedBinary() {
			((ProjectInternal) project).evaluate();
			CppTestExecutable testExecutable = testSuite.getBinaries().get().stream().map(CppTestExecutable.class::cast).findFirst().orElseThrow();
			assertThat(testedBinaryOf(testExecutable), providerOf(allOf(named("mainDebug"), isA(CppExecutable.class))));
		}
	}

	@Nested
	class WhenCppLibraryPluginApplied {
		@BeforeEach
		void setup() {
			project.getPlugins().apply("cpp-library");
		}

		@Test
		void useCppLibraryAsTestedComponent() {
			assertThat(testedComponentOf(testSuite), providerOf(allOf(named("main"), isA(CppLibrary.class))));
		}

		@Test
		void useDebugSharedLibraryAsTestedBinaryOnBothLinkages() {
			project.getExtensions().configure("library", (CppLibrary library) -> library.getLinkage().set(Arrays.asList(Linkage.values())));
			((ProjectInternal) project).evaluate();
			CppTestExecutable testExecutable = testSuite.getBinaries().get().stream().map(CppTestExecutable.class::cast).findFirst().orElseThrow();
			assertThat(testedBinaryOf(testExecutable), providerOf(allOf(named("mainDebugShared"), isA(CppSharedLibrary.class))));
		}

		@Test
		void useDebugSharedLibraryAsTestedBinary() {
			project.getExtensions().configure("library", (CppLibrary library) -> library.getLinkage().set(Collections.singleton(Linkage.SHARED)));
			((ProjectInternal) project).evaluate();
			CppTestExecutable testExecutable = testSuite.getBinaries().get().stream().map(CppTestExecutable.class::cast).findFirst().orElseThrow();
			assertThat(testedBinaryOf(testExecutable), providerOf(allOf(named("mainDebug"), isA(CppSharedLibrary.class))));
		}

		@Test
		void useDebugStaticLibraryAsTestedBinary() {
			project.getExtensions().configure("library", (CppLibrary library) -> library.getLinkage().set(Collections.singleton(Linkage.STATIC)));
			((ProjectInternal) project).evaluate();
			CppTestExecutable testExecutable = testSuite.getBinaries().get().stream().map(CppTestExecutable.class::cast).findFirst().orElseThrow();
			assertThat(testedBinaryOf(testExecutable), providerOf(allOf(named("mainDebug"), isA(CppStaticLibrary.class))));
		}
	}
}
