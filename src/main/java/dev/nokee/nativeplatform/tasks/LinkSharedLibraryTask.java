package dev.nokee.nativeplatform.tasks;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Internal;

import javax.inject.Inject;

/*private*/ abstract /*final*/ class LinkSharedLibraryTask extends org.gradle.nativeplatform.tasks.LinkSharedLibrary implements LinkAbiAware {
	@Inject
	public LinkSharedLibraryTask() {}

	@Override
	@Internal
	public ConfigurableFileCollection getLibs() {
		return super.getLibs();
	}
}
