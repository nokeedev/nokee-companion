package dev.nokee.nativeplatform.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Set;

/**
 * Hashes an {@link AbiBinaryHashCode} out of a binary. A reader is stateless and reusable: a single instance
 * can hash many channels. It validates its own magic, so it can be used standalone. It never returns
 * {@code null}: it returns a model, throws {@link IllegalArgumentException} when handed the wrong
 * kind of file (magic mismatch), or throws {@link NotASharedLibraryException} when the file is of
 * the right format but exports no shared-library ABI.
 */
interface AbiBinaryHasher {
	AbiBinaryHashCode hash(FileChannel channel) throws IOException;

	interface HasExportSymbols {
		/**
		 * {@return each exported symbol as a {@code (nameKey, payload)} pair} — {@code nameKey} (from the
		 * {@link SymbolNameHasher}) is matched against imported names for narrowing and keys the symbol in
		 * the snapshot; {@code payload} carries the remaining ABI attributes so a change is detected.
		 */
		Set<ExportedSymbol> getExportedSymbols();

		HasExportSymbols narrowExports(Set<Object> allImports, Set<Object> unresolved);
	}

	interface ExportedSymbol {
		Object getName();
	}

	interface HasImportSymbols {
		/**
		 * {@return the name identities this object imports (undefined external references)} — each is
		 * comparable to an {@link ExportedSymbol#getName()} so a shared library's exports can be narrowed
		 * to just the symbols actually imported.
		 */
		Set<Object> getImportedSymbols();
	}

	interface HasMembers {
		/**
		 * {@return the hash codes of this container's members} — e.g. the per-member object models of an
		 * archive. The container makes no assumption about what its members expose; a consumer traverses
		 * them and reads whatever capability ({@link HasImportSymbols}, {@link HasExportSymbols}) each has.
		 */
		Set<AbiBinaryHashCode> getMembers();
	}

	interface AbiBinaryHashCode {
		Type type();
	}

	enum Type {
		OBJECT_FILE, STATIC_LIB, DYNAMIC_LIB, UNKNOWN
	}

	interface Unknown {}

	interface HasLocation {
		File location();
	}
}
