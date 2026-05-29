package dev.nokee.nativeplatform.tasks;

import java.io.File;

final class StaticLibraryAbiModel implements AbiModel, LibraryFileAware {
	private final File location;

	StaticLibraryAbiModel(File location) {
		this.location = location;
	}

	@Override
	public File getFile() {
		return location;
	}

	@Override
	public String toString() {
		return "static lib '" + location + "'";
	}
}
