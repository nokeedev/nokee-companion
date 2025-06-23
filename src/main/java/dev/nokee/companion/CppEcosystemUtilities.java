package dev.nokee.companion;

import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.nativeplatform.*;
import org.gradle.nativeplatform.tasks.*;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

public abstract class CppEcosystemUtilities {
	private final TaskContainer tasks;
	private final ConfigurationContainer configurations;

	@Inject
	public CppEcosystemUtilities(TaskContainer tasks, ConfigurationContainer configurations) {
		this.tasks = tasks;
		this.configurations = configurations;
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
	 * {@return a configurable provider to the link task of the specified binary}
	 * @param binary  the target binary
	 */
	public TaskProvider<AbstractLinkTask> linkTaskOf(CppBinary binary) {
		return tasks.named(linkTaskName(binary), AbstractLinkTask.class);
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

	public static CppEcosystemUtilities forProject(Project project) {
		return project.getObjects().newInstance(CppEcosystemUtilities.class);
	}
}
