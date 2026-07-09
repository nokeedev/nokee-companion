package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class SharedLibraryAbiModel implements AbiModel {
	private static final long serialVersionUID = 1L;
	private final String soname; // nullable
	private final List<ExportedSymbol> exportedSymbols;

	SharedLibraryAbiModel(@Nullable String soname, List<ExportedSymbol> exportedSymbols) {
		this.soname = soname;
		this.exportedSymbols = exportedSymbols;
	}

	Optional<String> getSoname() {
		return Optional.ofNullable(soname);
	}

	List<ExportedSymbol> getExportedSymbols() {
		return exportedSymbols;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SharedLibraryAbiModel that = (SharedLibraryAbiModel) o;
		return Objects.equals(soname, that.soname) && exportedSymbols.equals(that.exportedSymbols);
	}

	@Override
	public int hashCode() {
		return Objects.hash(soname, exportedSymbols);
	}

	@Override
	public String toString() {
		return "shared lib " + exportedSymbols;
	}
}
