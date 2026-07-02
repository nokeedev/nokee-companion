package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

import javax.inject.Inject;

abstract /*final*/ class PeExportedSymbol implements ExportedSymbol {
	@Inject
	public PeExportedSymbol(String name, int ordinal) {
		getName().set(name);
		getOrdinal().set(ordinal);
	}

	@Override
	@Input
	public abstract Property<String> getName();

	@Input
	abstract Property<Integer> getOrdinal();
}
