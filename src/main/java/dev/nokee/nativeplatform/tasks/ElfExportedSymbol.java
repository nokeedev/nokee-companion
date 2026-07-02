package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

import javax.inject.Inject;

abstract /*final*/ class ElfExportedSymbol implements ExportedSymbol {
	@Inject
	public ElfExportedSymbol(String name, int binding, int type) {
		getName().set(name);
		getBinding().set(binding);
		getType().set(type);
	}

	@Override
	@Input
	public abstract Property<String> getName();

	@Input
	abstract Property<Integer> getBinding();

	@Input
	abstract Property<Integer> getType();

	@Override
	public String toString() {
		return "exported symbol { name: '" + getName().get() + "', binding=" + getBinding().get() +
			", type=" + getType().get() +
			'}';
	}
}
