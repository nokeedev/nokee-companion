package dev.nokee.nativeplatform.tasks;

import org.gradle.api.tasks.Input;

import java.io.Serializable;

final class MachOExportedSymbol implements ExportedSymbol, Serializable {
	private final String name;
	private final boolean weak;

	MachOExportedSymbol(String name, boolean weak) {
		this.name = name;
		this.weak = weak;
	}

	@Override
	@Input
	public String getName() {
		return name;
	}

	@Input
	boolean isWeak() {
		return weak;
	}

	@Override
	public String toString() {
		return (isWeak() ? "weak " : "") + "exported symbol { name: '" + getName() + "' }";
	}
}
