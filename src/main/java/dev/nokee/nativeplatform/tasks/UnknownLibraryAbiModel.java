package dev.nokee.nativeplatform.tasks;

import java.io.File;

final class UnknownLibraryAbiModel implements AbiModel, LibraryFileAware {
	private final File location;

	UnknownLibraryAbiModel(File location) {
		this.location = location;
	}

	@Override
	public File getFile() {
		return location;
	}
}
