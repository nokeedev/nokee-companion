package dev.nokee.legacy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import javax.inject.Inject;

/*public*/ abstract /*final*/ class LegacySupportPlugin implements Plugin<Project> {
	@Inject
	public LegacySupportPlugin() {}

    public void apply(Project project) {
		project.getPluginManager().apply(GradleIssue29744Fix.class);
		project.getPluginManager().apply(CppCompileTask.Rule.class);
    }
}
