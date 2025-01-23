package dev.nokee.legacy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import javax.inject.Inject;

/*public*/ abstract /*final*/ class LegacySupportPlugin implements Plugin<Project> {
	@Inject
	public LegacySupportPlugin() {}

    public void apply(Project project) {
		project.getPluginManager().apply(ObjectFiles.Rule.class);
		project.getPluginManager().apply(CompileTasks.Rule.class);
		project.getPluginManager().apply(CppSourceFiles.Rule.class);
		project.getPluginManager().apply(CppCompileTask.Rule.class);
		project.getPluginManager().apply(CppBinaryObjects.Rule.class);
		project.getPluginManager().apply(CppBinaryTaskExtensions.Rule.class);

		project.getPluginManager().apply(GradleIssue29492Fix.class);
		project.getPluginManager().apply(GradleIssue29744Fix.class);
		project.getPluginManager().apply(GradleIssueXXXDependsOnPublicGeneratedHeadersAndMultiplePublicHeadersFix.class);
		project.getPluginManager().apply(GradleIssueXXXHeaderNormalizationWindowsFix.class);
		project.getPluginManager().apply(GradleIssueVersionCatalogueFix.class);

		// TODO: Source include (cxx)
		// TODO: includes .i/.ii

		// TODO: Copy variant (or part of) - work around the lack of variants
		//   dup compile task
		//   dup link task
		//   dup configuration
		//   etc.
    }
}
