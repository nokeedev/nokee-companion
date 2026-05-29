package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Nested;

import javax.inject.Inject;
import java.util.List;

abstract /*final*/ class SharedLibraryAbiModel implements AbiModel {
	@Inject
	public SharedLibraryAbiModel(List<ExportedSymbol> exportedSymbols) {
		getExportedSymbols().set(exportedSymbols);
	}

	@Nested
	abstract ListProperty<ExportedSymbol> getExportedSymbols();

	@Override
	public String toString() {
		return "shared lib " + getExportedSymbols().get();
	}
}
