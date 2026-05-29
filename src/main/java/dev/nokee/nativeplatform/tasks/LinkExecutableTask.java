package dev.nokee.nativeplatform.tasks;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Internal;

import javax.inject.Inject;

/*private*/ abstract /*final*/ class LinkExecutableTask extends org.gradle.nativeplatform.tasks.LinkExecutable implements LinkAbiAware {
	@Inject
	public LinkExecutableTask() {}

	@Override
	@Internal
	public ConfigurableFileCollection getLibs() {
		return super.getLibs();
	}
}
