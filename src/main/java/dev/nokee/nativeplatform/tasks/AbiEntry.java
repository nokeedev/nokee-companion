package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;

final class AbiEntry {
	@Nullable final String soname;
	final AbiModel model;

	AbiEntry(@Nullable String soname, AbiModel model) {
		this.soname = soname;
		this.model = model;
	}
}
