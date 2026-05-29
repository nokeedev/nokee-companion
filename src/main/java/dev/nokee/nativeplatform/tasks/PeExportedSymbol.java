package dev.nokee.nativeplatform.tasks;

import org.gradle.api.tasks.Input;

final class PeExportedSymbol implements ExportedSymbol {
	private final String name;
	private final int ordinal;

	PeExportedSymbol(String name, int ordinal) {
		this.name = name;
		this.ordinal = ordinal;
	}

	@Override
	@Input
	public String getName() {
		return name;
	}

	@Input
	int getOrdinal() {
		return ordinal;
	}
}
