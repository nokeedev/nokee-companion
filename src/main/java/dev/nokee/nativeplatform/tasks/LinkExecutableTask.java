package dev.nokee.nativeplatform.tasks;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Internal;

import javax.inject.Inject;

/*private*/ abstract /*final*/ class LinkExecutableTask extends org.gradle.nativeplatform.tasks.LinkExecutable implements LinkAbiAware {
	@Inject
	public LinkExecutableTask() {
		linkSuperClassLibsField();
	}

	//region libs override
	private void linkSuperClassLibsField() {
		super.getLibs().from(getLibs());
	}

	@Override
	@Internal
	public ConfigurableFileCollection getLibs() {
		return getLinkAbi().getLibs();
	}

	@Override
	public void setLibs(FileCollection libs) {
		getLibs().setFrom(libs);
	}

	@Override
	public void lib(Object libs) {
		getLibs().from(libs);
	}
	//endregion
}
