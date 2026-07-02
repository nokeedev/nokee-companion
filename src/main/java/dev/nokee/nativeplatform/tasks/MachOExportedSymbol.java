package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

import javax.inject.Inject;

abstract /*final*/ class MachOExportedSymbol implements ExportedSymbol {
	@Inject
	public MachOExportedSymbol(String name, boolean weak) {
		getName().set(name);
		getWeak().set(weak);
	}

	@Override
	@Input
	public abstract Property<String> getName();

	@Input
	abstract Property<Boolean> getWeak();

	@Override
	public String toString() {
		return (getWeak().get() ? "weak " : "") + "exported symbol { name: '" + getName().get() + "' }";
	}
}
