package dev.nokee.companion.features;

import dev.nokee.language.cpp.tasks.CppCompile;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.NonExtensible;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.nativeplatform.*;
import org.gradle.nativeplatform.tasks.CreateStaticLibrary;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.*;

// Helper service
@NonExtensible
public abstract class CppBinaryAccessors {
	private final ConfigurationContainer configurations;
	private final TaskContainer tasks;

	@Inject
	public CppBinaryAccessors(ConfigurationContainer configurations, TaskContainer tasks) {
		this.configurations = configurations;
		this.tasks = tasks;
	}

	@SuppressWarnings("UnstableApiUsage")
	public <T extends CppBinary & ComponentWithLinkUsage> NamedDomainObjectProvider<Configuration> linkElementsOf(T binary) {
		return configurations.named(linkElementsConfigurationName(binary));
	}

	@SuppressWarnings("UnstableApiUsage")
	public <T extends CppBinary & ComponentWithRuntimeUsage> NamedDomainObjectProvider<Configuration> runtimeElementsOf(T binary) {
		return configurations.named(runtimeElementsConfigurationName(binary));
	}

	public NamedDomainObjectProvider<Configuration> cppCompileOf(CppBinary binary) {
		return configurations.named(cppCompileConfigurationName(binary));
	}

	public NamedDomainObjectProvider<Configuration> nativeLinkOf(CppBinary binary) {
		return configurations.named(nativeLinkConfigurationName(binary));
	}

	public NamedDomainObjectProvider<Configuration> nativeRuntimeOf(CppBinary binary) {
		return configurations.named(nativeRuntimeConfigurationName(binary));
	}

	public TaskProvider<CppCompile> compileTaskOf(CppBinary binary) {
		return tasks.named(compileTaskName(binary), CppCompile.class);
	}

	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<LinkExecutable> linkTaskOf(ComponentWithExecutable binary) {
		return tasks.named(linkTaskName((CppBinary) binary), LinkExecutable.class);
	}

	@SuppressWarnings("UnstableApiUsage")
	public <T extends CppBinary & ComponentWithInstallation> TaskProvider<InstallExecutable> installTaskOf(T binary) {
		return tasks.named(installTaskName(binary), InstallExecutable.class);
	}

	@SuppressWarnings("UnstableApiUsage")
	public TaskProvider<LinkSharedLibrary> linkTaskOf(ComponentWithSharedLibrary binary) {
		return tasks.named(linkTaskName((CppBinary) binary), LinkSharedLibrary.class);
	}

	@SuppressWarnings("UnstableApiUsage")
	public <T extends CppBinary & ComponentWithStaticLibrary> TaskProvider<CreateStaticLibrary> createTaskOf(T binary) {
		return tasks.named(linkTaskName(binary), CreateStaticLibrary.class);
	}

	public TaskProvider<RunTestExecutable> runTask(CppTestExecutable binary) {
		return tasks.named(runTaskName(binary), RunTestExecutable.class);
	}
}
