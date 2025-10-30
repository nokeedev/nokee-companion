package dev.nokee.companion.features;

import dev.nokee.companion.NativeCompanionExtension;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.platform.OperatingSystem;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.testfixtures.ProjectBuilder;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;

class RPathLinkFlagsIntegrationTests {
	Project project;
	ProjectDependency dependency;

	@BeforeEach
	void setup(@TempDir Path testDirectory) throws IOException {
		testDirectory = testDirectory.toFile().getCanonicalFile().toPath();
		project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		project.getPluginManager().apply("dev.nokee.native-companion");
		project.getExtensions().getByType(NativeCompanionExtension.class).enableFeaturePreview("rpath-link-flags");

		Project directDep = configureSharedLib(ProjectBuilder.builder().withName("a").withParent(project).withProjectDir(Files.createDirectories(testDirectory.resolve("projA")).toFile()).build());
		Project indirect1 = configureStaticLib(ProjectBuilder.builder().withName("b").withParent(project).withProjectDir(Files.createDirectories(testDirectory.resolve("projB")).toFile()).build());
		Project indirect2 = configureSharedLib(ProjectBuilder.builder().withName("c").withParent(project).withProjectDir(Files.createDirectories(testDirectory.resolve("projB")).toFile()).build());
		directDep.getDependencies().add("implementation", indirect1);
		directDep.getDependencies().add("implementation", indirect2);

		dependency = (ProjectDependency) project.getDependencies().create(directDep);
	}

	Project configureSharedLib(Project project) throws IOException {
		File libFile = project.file("lib" + project.getName() + ".so");
		libFile.createNewFile();
		Configuration implementation = project.getConfigurations().dependencyScope("implementation").get();
		project.getConfigurations().consumable("linkElements", it -> {
			it.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.NATIVE_LINK));
				attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED);
			});
			it.outgoing(outgoing -> {
				outgoing.artifact(libFile);
			});
		}).get();
		project.getConfigurations().consumable("runtimeElements", it -> {
			it.extendsFrom(implementation);
			it.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.NATIVE_RUNTIME));
				attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.SHARED);
			});
			it.outgoing(outgoing -> {
				outgoing.artifact(libFile);
			});
		}).get();
		return project;
	}

	Project configureStaticLib(Project project) throws IOException {
		File libFile = project.file("lib" + project.getName() + ".a");
		libFile.createNewFile();
		Configuration implementation = project.getConfigurations().dependencyScope("implementation").get();
		project.getConfigurations().consumable("linkElements", it -> {
			it.extendsFrom(implementation);
			it.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.NATIVE_LINK));
				attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.STATIC);
			});
			it.outgoing(outgoing -> {
				outgoing.artifact(libFile);
			});
		}).get();
		project.getConfigurations().consumable("runtimeElements", it -> {
			it.extendsFrom(implementation);
			it.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.NATIVE_RUNTIME));
				attributes.attribute(CppBinary.LINKAGE_ATTRIBUTE, Linkage.STATIC);
			});
		}).get();
		return project;
	}

	NativePlatform linuxPlatform() {
		return Mockito.mock(NativePlatform.class, new Answer() {
			@Override
			public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
				if (invocationOnMock.getMethod().getName().equals("getOperatingSystem")) {
					return Mockito.mock(OperatingSystem.class, new Answer<>() {
						@Override
						public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
							return invocationOnMock.getMethod().getName().equals("isLinux");
						}
					});
				}
				return null;
			}
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"cpp-application", "cpp-library", "cpp-unit-test"})
	void hasRPathLinkFlags(String pluginIdUnderTest) {
		project.getPluginManager().apply(pluginIdUnderTest);

		project.getExtensions().getByType(CppComponent.class).getDependencies().implementation(dependency);

		// Mock a Gcc on Linux linking scenario
		((ProjectInternal) project).evaluate();
		project.getTasks().withType(AbstractLinkTask.class).configureEach(task -> {
			task.getToolChain().set(Mockito.mock(Gcc.class));
			task.getTargetPlatform().set(linuxPlatform());
		});

		assertThat(project.getTasks().withType(AbstractLinkTask.class), everyItem(linkerArgs("-Wl,-rpath-link=projB")));
	}

	static Matcher<AbstractLinkTask> linkerArgs(String... flags) {
		return new FeatureMatcher<>(hasItems(flags), "", "") {
			@Override
			protected Iterable<String> featureValueOf(AbstractLinkTask actual) {
				return actual.getLinkerArgs().get();
			}
		};
	}
}
