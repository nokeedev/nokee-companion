package dev.nokee.companion;

import dev.nokee.commons.names.CppNames;
import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.NonExtensible;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.nativeplatform.*;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;

import javax.inject.Inject;
import java.util.Optional;
import java.util.function.Supplier;

import static dev.nokee.commons.names.CppNames.*;

@NonExtensible
public abstract class CppEcosystemUtilities {
	private final TaskContainer tasks;
	private final ConfigurationContainer configurations;
	private final ShadowProperties properties;

	@Inject
	public CppEcosystemUtilities(TaskContainer tasks, ConfigurationContainer configurations, Project project) {
		this.tasks = tasks;
		this.configurations = configurations;
		this.properties = Optional.ofNullable((ShadowProperties) project.getExtensions().findByName("$shadow-properties")).orElseGet(() -> project.getExtensions().create("$shadow-properties", ShadowProperties.class));
	}

	/**
	 * {@return a configurable provider to the compile task of the specified binary}
	 * @param binary  the target binary
	 */
	public TaskProvider<CppCompile> compileTaskOf(CppBinary binary) {
		return tasks.named(compileTaskName(binary), CppCompile.class);
	}

	/**
	 * {@return a configurable provider to the link task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<LinkExecutable> linkTaskOf(ComponentWithExecutable binary) {
		assert binary instanceof CppBinary;
		return tasks.named(linkTaskName((CppBinary) binary), LinkExecutable.class);
	}

	/**
	 * {@return a configurable provider to the install task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<InstallExecutable> installTaskOf(ComponentWithInstallation binary) {
		assert binary instanceof CppBinary;
		return tasks.named(installTaskName((CppBinary) binary), InstallExecutable.class);
	}

	/**
	 * {@return a configurable provider to the link task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<LinkSharedLibrary> linkTaskOf(ComponentWithSharedLibrary binary) {
		assert binary instanceof CppBinary;
		return tasks.named(linkTaskName((CppBinary) binary), LinkSharedLibrary.class);
	}

	/**
	 * {@return a configurable provider to the create task of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<CreateStaticLibrary> createTaskOf(ComponentWithStaticLibrary binary) {
		assert binary instanceof CppBinary;
		return tasks.named(createTaskName((CppBinary) binary), CreateStaticLibrary.class);
	}

	/**
	 * {@return a configurable provider to the run task of the specified binary}
	 * @param binary  the target binary
	 */
	public TaskProvider<RunTestExecutable> runTaskOf(CppTestExecutable binary) {
		return tasks.named(runTaskName(binary), RunTestExecutable.class);
	}

	/**
	 * {@return a configurable provider to the link elements configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public NamedDomainObjectProvider<Configuration> linkElementsOf(ComponentWithLinkUsage binary) {
		assert binary instanceof CppBinary;
		return configurations.named(linkElementsConfigurationName((CppBinary) binary));
	}

	/**
	 * {@return a configurable provider to the runtime elements configuration of the specified binary}
	 * @param binary  the target binary
	 */
	@SuppressWarnings("UnstableApiUsage")
	public NamedDomainObjectProvider<Configuration> runtimeElementsOf(ComponentWithRuntimeUsage binary) {
		assert binary instanceof CppBinary;
		return configurations.named(runtimeElementsConfigurationName((CppBinary) binary));
	}

	/**
	 * {@return a configurable provider to the compile only dependency bucket of the specified component}
	 * @param component  the target component
	 */
	public NamedDomainObjectProvider<Configuration> compileOnlyOf(CppComponent component) {
		return configurations.named(CppNames.of(component).configurationName("compileOnly").toString());
	}

	/**
	 * {@return a configurable provider to the compile only API dependency bucket of the specified library}
	 * @param library  the target component
	 */
	public NamedDomainObjectProvider<Configuration> compileOnlyApiOf(CppLibrary library) {
		return configurations.named(CppNames.of(library).configurationName("compileOnlyApi").toString());
	}

	/**
	 * {@return a configurable provider to the cpp compile configuration of the specified binary}
	 * @param binary  the target binary
	 */
	public NamedDomainObjectProvider<Configuration> cppCompileOf(CppBinary binary) {
		return configurations.named(cppCompileConfigurationName(binary));
	}

	/**
	 * {@return a configurable provider to the native link configuration of the specified binary}
	 * @param binary  the target binary
	 */
	public NamedDomainObjectProvider<Configuration> nativeLinkOf(CppBinary binary) {
		return configurations.named(nativeLinkConfigurationName(binary));
	}

	/**
	 * {@return a configurable provider to the native runtime configuration of the specified binary}
	 * @param binary  the target binary
	 */
	public NamedDomainObjectProvider<Configuration> nativeRuntimeOf(CppBinary binary) {
		return configurations.named(nativeRuntimeConfigurationName(binary));
	}

	/**
	 * Returns a property to configure optimization of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the optimized property
	 */
	@SuppressWarnings("UnstableApiUsage")
	public ShadowProperty<Boolean> optimizationOf(CppBinary binary) {
		return properties.forObject(binary).get("optimized", binary::isOptimized);
	}

	/**
	 * Returns a property to configure debuggability of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the debuggable property
	 */
	@SuppressWarnings("UnstableApiUsage")
	public ShadowProperty<Boolean> debuggabilityOf(CppBinary binary) {
		return properties.forObject(binary).get("debuggable", binary::isDebuggable);
	}

	/**
	 * Returns a property to configure compile include path of a C++ binary.
	 * Due to the strict nature of C++ binary, we are using shadow properties to perform the mutation.
	 * All code relying on this value should be made aware of a potential shadow value.
	 *
	 * @param binary  the C++ binary
	 * @return the compile include path property
	 */
	public ShadowProperty<FileCollection> compileIncludePathOf(CppBinary binary) {
		return properties.forObject(binary).get("compileIncludePath", binary::getCompileIncludePath);
	}

	/**
	 * Returns the shadow property of {@link CppBinary#getObjects()}.
	 *
	 * @param binary  the binary with the objects
	 * @return the property
	 */
	@SuppressWarnings("UnstableApiUsage")
	public ShadowProperty<FileCollection> objectsOf(ComponentWithObjectFiles binary) {
		return properties.forObject(binary).get("objects", binary::getObjects);
	}

	/**
	 * Returns the shadow property of {@link CppBinary#getCppSource()}.
	 *
	 * @param binary  the binary with C++ source.
	 * @return the property
	 */
	public ShadowProperty<FileCollection> cppSourceOf(CppBinary binary) {
		return properties.forObject(binary).get("cppSource", binary::getCppSource);
	}

	/**
	 * Returns the shadow property of {@link CppComponent#getCppSource()}.
	 *
	 * @param component  the component with C++ source.
	 * @return the property
	 */
	public ShadowProperty<FileCollection> cppSourceOf(CppComponent component) {
		return properties.forObject(component).get("cppSource", component::getCppSource);
	}

	public static CppEcosystemUtilities forProject(Project project) {
		return project.getObjects().newInstance(CppEcosystemUtilities.class);
	}

	/*private*/ static abstract class ShadowProperties {
		@Inject
		public ShadowProperties() {}

		public Registry forObject(Object self) {
			return new Registry(self);
		}

		public static class Registry {
			private final Object target;

			public Registry(Object target) {
				this.target = target;
			}

			public <T> ShadowProperty<T> get(String propertyName, Supplier<T> getter) {
				return ShadowProperty.of(target, propertyName, getter);
			}
		}
	}
}
