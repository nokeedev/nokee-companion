package dev.nokee.nativeplatform.tasks;

import org.gradle.api.tasks.Input;

import java.io.Serializable;

final class ElfExportedSymbol implements ExportedSymbol, Serializable {
	private final String name;
	private final int binding;
	private final int type;

	ElfExportedSymbol(String name, int binding, int type) {
		this.name = name;
		this.binding = binding;
		this.type = type;
	}

	@Override
	@Input
	public String getName() {
		return name;
	}

	@Input
	int getBinding() {
		return binding;
	}

	@Input
	int getType() {
		return type;
	}

	@Override
	public String toString() {
		return "exported symbol { name: '" + name + "', binding=" + binding +
			", type=" + type +
			'}';
	}
}
