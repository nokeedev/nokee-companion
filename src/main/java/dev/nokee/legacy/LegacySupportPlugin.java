package dev.nokee.legacy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginAware;

import javax.inject.Inject;

/*public*/ abstract /*final*/ class LegacySupportPlugin<TargetType extends PluginAware & ExtensionAware> implements Plugin<TargetType> {
	@Inject
	public LegacySupportPlugin() {}

    public void apply(TargetType target) {
		if (target instanceof Project) {
			doApply((Project) target);
		} else if (target instanceof Settings) {
			doApply((Settings) target);
		}
	}

	private void doApply(Settings settings) {
		settings.getGradle().allprojects(project -> project.getPluginManager().apply("dev.nokee.native-companion"));
	}

	private void doApply(Project project) {
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
		project.getPluginManager().apply(GradleIssueIncrementalCompilationAfterFailureFix.class);
		// TODO: Source include (cxx)
		// TODO: includes .i/.ii

		// TODO: Copy variant (or part of) - work around the lack of variants
		//   dup compile task
		//   dup link task
		//   dup configuration
		//   etc.
    }
}
