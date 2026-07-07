package dev.nokee.nativeplatform.tasks;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.nativeplatform.internal.LinkerSpec;
import org.gradle.process.CommandLineArgumentProvider;

import javax.inject.Inject;
import java.util.Collections;

/*private*/ abstract /*final*/ class LinkSharedLibraryTask extends org.gradle.nativeplatform.tasks.LinkSharedLibrary implements LinkAbiAware, LinkTask {
	@Inject
	public LinkSharedLibraryTask() {
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

	@Internal
	@Override
	public ListProperty<String> getLinkerArgs() {
		return getOptions().getLinkerArgs();
	}

	@Override
	protected LinkerSpec createLinkerSpec() {
		LinkerSpec result = super.createLinkerSpec();
		for (CommandLineArgumentProvider argProvider : getOptions().getLinkerArgumentProviders().getOrElse(Collections.emptyList())) {
			for (String arg : argProvider.asArguments()) {
				result.getArgs().add(arg);
			}
		}
		return result;
	}
}
