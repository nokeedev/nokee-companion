package dev.nokee.legacy;

import org.gradle.api.Project;
import org.gradle.api.Plugin;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;

import javax.inject.Inject;

import static dev.nokee.commons.names.CppNames.compileTaskName;

/*public*/ abstract /*final*/ class LegacySupportPlugin implements Plugin<Project> {
	@Inject
	public LegacySupportPlugin() {}

    public void apply(Project project) {
		project.getPluginManager().apply(CppCompileTask.Rule.class);
    }
}
