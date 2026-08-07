package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

import static dev.nokee.nativeplatform.tasks.MachOBlob.*;

/**
 * Reads both sides of a Mach-O image's ABI from its {@code LC_SYMTAB}: the external symbols a dylib
 * <em>defines</em> ({@code MH_DYLIB}, {@code MH_DYLIB_STUB}) and the undefined external symbols an object
 * <em>references</em> — what a linker resolves against. Which side is read follows the image's filetype,
 * so the same walk of the header and load commands serves a shared library and an object file.
 *
 * <p>Reads the image at {@code [base, base + size)} rather than the whole channel, so a standalone object
 * and an archive member are handled the same way. A fat binary is resolved to its first architecture.
 */
// TODO: migrate this as premade walker/reader/visitor for what we need (object file -> import, dylib -> exports)
final class MachOBinaryHasher implements AbiBinaryHasher {
	private static final int N_STAB = 0xe0;
	private static final int N_EXT = 0x01;
	private static final int N_TYPE = 0x0e;
	private static final int N_UNDF = 0x00;
	private static final int N_WEAK_DEF = 0x0080;

	/** Reads a whole file, which is expected to be a shared library. */
	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		MachOBlob blob = MachOBlob.parse(source);
		List<String> installNames = new ArrayList<>();
		Set<ExportedSymbol> exports = new LinkedHashSet<>();
		visitSharedLib(blob, new ExportOrInstallNameVisitor() {
			@Override
			public void visitInstallName(String installName) {
				installNames.add(installName);
			}

			@Override
			public void visitExportSymbol(String name, boolean weakBinding) {
				exports.add(new MachOExportedSymbol(name, weakBinding));
			}
		});
		return new MachOHashCode(String.join("\0", installNames), exports);
	}

	public void visitSharedLib(MachOBlob blob, ExportOrInstallNameVisitor visitor) {
		if (blob instanceof MachOBlob.MachOUniversalBlob) {
			for (MachOBlob.MachOImageBlob architecture : ((MachOBlob.MachOUniversalBlob) blob).architectures()) {
				hash(architecture, visitor);
			}
		} else if (blob instanceof MachOBlob.MachOImageBlob) {
			hash((MachOBlob.MachOImageBlob) blob, visitor);
		}
	}

	interface ImportVisitor {
		void visitImportSymbol(String name);
	}

	interface ExportOrInstallNameVisitor {
		void visitInstallName(String installName);
		void visitExportSymbol(String name, boolean weakBinding);
	}

	private void hash(MachOBlob.MachOImageBlob image, ExportOrInstallNameVisitor visitor) {
		assert image.filetype() == MH_DYLIB || image.filetype() == MH_DYLIB_STUB;

		MachOBlob.MachOSymtabCommand symtab = null;
		MachOBlob.MachODysymtabCommand dysymtab = null;
		for (MachOBlob.MachOLoadCommand loadCommand : image.loadCommands()) {
			if (loadCommand instanceof MachOBlob.MachODylibCommand) {
				visitor.visitInstallName(((MachOBlob.MachODylibCommand) loadCommand).name());
			} else if (loadCommand instanceof MachOBlob.MachOSymtabCommand) {
				symtab = (MachOBlob.MachOSymtabCommand) loadCommand;
			} else if (loadCommand instanceof MachOBlob.MachODysymtabCommand) {
				dysymtab = (MachOBlob.MachODysymtabCommand) loadCommand;
			}

			if (symtab != null && dysymtab != null) {
				break;
			}
		}

		assert symtab != null;
		assert dysymtab != null;

		MachOBlob.MachOStringTable strtab = symtab.strings();

		for (MachOBlob.MachOSymbol symbol : symtab.symbols().range(dysymtab.iextdefsym(), dysymtab.iextdefsym() + dysymtab.nextdefsym())) {
			int nType = symbol.type();
			if ((nType & N_STAB) != 0) continue;         // debug symbol
			if ((nType & N_EXT) == 0) continue;          // not external
			if ((nType & N_TYPE) == N_UNDF) continue;    // referenced here, not defined

			// Read each name on demand from the string table instead of loading the whole table.
			String name = strtab.get(symbol.strx());
			if (!name.isEmpty()) {
				visitor.visitExportSymbol(name, (symbol.desc() & N_WEAK_DEF) != 0);
			}
		}
	}

	public void visitImports(MachOBlob blob, ImportVisitor visitor) {
		if (blob instanceof MachOBlob.MachOUniversalBlob) {
			for (MachOBlob.MachOImageBlob architecture : ((MachOBlob.MachOUniversalBlob) blob).architectures()) {
				visitImports(architecture, visitor);
			}
		} else if (blob instanceof MachOBlob.MachOImageBlob) {
			visitImports((MachOBlob.MachOImageBlob) blob, visitor);
		}
	}

	public void visitImports(MachOBlob.MachOImageBlob image, ImportVisitor visitor) {
		assert image.filetype() == MH_OBJECT;

		MachOBlob.MachOSymtabCommand symtab = null;
		for (MachOBlob.MachOLoadCommand loadCommand : image.loadCommands()) {
			if (loadCommand instanceof MachOBlob.MachOSymtabCommand) {
				symtab = (MachOBlob.MachOSymtabCommand) loadCommand;
				break;
			}
		}

		assert symtab != null;

		MachOBlob.MachOStringTable strtab = symtab.strings();

		for (MachOBlob.MachOSymbol symbol : symtab.symbols()) {

			int nType = symbol.type();
			long nValue = symbol.value();

			if ((nType & N_STAB) != 0) continue;         // debug symbol
			if ((nType & N_EXT) == 0) continue;          // not external
			if ((nType & N_TYPE) != N_UNDF) continue;    // defined here, not an import
			if (nValue != 0) continue;                   // common symbol (tentative definition), not an import
//			if (strx == 0) continue;

			// Read each name on demand from the string table instead of loading the whole table.
			String name = strtab.get(symbol.strx());
			if (!name.isEmpty()) {
				visitor.visitImportSymbol(name);
			}
		}
	}

	private static final class MachOExportedSymbol extends AbstractMap<String, Object> implements ExportedSymbol {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public MachOExportedSymbol(String name, boolean isWeakBinding) {
			entries.add(new SimpleEntry<>("name", name));
			entries.add(new SimpleEntry<>("isWeakBinding", isWeakBinding));
		}

		@Override
		public Object getName() {
			return get("name");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}

	private static final class MachOHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasExportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public MachOHashCode(String installName, Set<ExportedSymbol> symbols) {
			entries.add(new SimpleEntry<>("installName", installName));
			entries.add(new SimpleEntry<>("symbols", symbols));
		}

		@Override
		public Type type() {
			return Type.DYNAMIC_LIB;
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<ExportedSymbol> getExportedSymbols() {
			return (Set<ExportedSymbol>) get("symbols");
		}

		@Override
		public HasExportSymbols narrowExports(Set<Object> allImports, Set<Object> unresolved) {
			Set<ExportedSymbol> retained = new LinkedHashSet<>();
			for (ExportedSymbol symbol : getExportedSymbols()) {
				if (allImports.contains(symbol.getName())) {
					retained.add(symbol);
					unresolved.remove(symbol.getName());
				}
			}
			return new MachOHashCode((String) get("installName"), retained);
		}
	}

	private static final class MachOImportHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasImportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		MachOImportHashCode(Set<Object> importedSymbols) {
			entries.add(new SimpleEntry<>("symbols", importedSymbols));
		}

		@Override
		public Type type() {
			return Type.OBJECT_FILE;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<Object> getImportedSymbols() {
			return (Set<Object>) get("symbols");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}
}
