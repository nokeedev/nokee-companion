package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import javax.inject.Inject;
import java.util.List;

abstract /*final*/ class SharedLibraryAbiModel implements AbiModel {
	@Inject
	public SharedLibraryAbiModel(java.util.Optional<String> soname, List<ExportedSymbol> exportedSymbols) {
		getSoname().set(soname.orElse(null));
		getExportedSymbols().set(exportedSymbols);
	}

	@Input
	@Optional
	abstract Property<String> getSoname();

	@Nested
	abstract ListProperty<ExportedSymbol> getExportedSymbols();

	@Override
	public String toString() {
		return "shared lib " + getExportedSymbols().get();
	}
}
