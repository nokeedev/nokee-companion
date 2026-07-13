package dev.nokee.nativeplatform.tasks;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.internal.hash.HashCode;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;

final class SharedLibraryAbiModel implements AbiModel {
	private static final long serialVersionUID = 1L;
	private final String soname; // nullable
	private final Set<HashCode> exportedSymbols;

	SharedLibraryAbiModel(@Nullable String soname, Set<HashCode> exportedSymbols) {
		this.soname = soname;
		this.exportedSymbols = exportedSymbols;
	}

	@Input
	@Nullable
	@Optional
	String getSoname() {
		return soname;
	}

	@Input
	Set<HashCode> getExportedSymbols() {
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
