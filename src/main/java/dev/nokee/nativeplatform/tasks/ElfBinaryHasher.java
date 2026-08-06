package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.function.Consumer;

// Care was taken to avoid as many condition and allocation as possible
final class ElfBinaryHasher implements AbiBinaryHasher {
	private static final int ET_REL = 1; // for e_type
	private static final int ET_DYN = 3; // for e_type
	private static final int SHT_SYMTAB = 2; // for sh_type
	private static final int SHT_DYNAMIC = 6; // for sh_type
	private static final int SHT_DYNSYM = 11; // for sh_type
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;

	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		ElfBlob blob = ElfBlob.parse(source);
		int e_type = blob.e_type();

		if (e_type == ET_DYN) return hash(blob);
		if (e_type == ET_REL) return hashRel(blob);
		throw new IllegalStateException("ELF file is not parsable (e_type=" + e_type + ")");
	}

	private static final Set<Integer> dyn_types = new HashSet<>();
	private static final Set<Integer> rel_types = Collections.singleton(SHT_SYMTAB);
	static {
		dyn_types.add(SHT_DYNAMIC);
		dyn_types.add(SHT_DYNSYM);
	}

	private ElfBlob.ElfStringTable loadDynstr(ElfBlob.ElfSectionHeader ref) throws IOException {
		int sh_link = ref.link();
		if (sh_link >= 0) { // if we overflow, there will be an exception
			return new ElfBlob.ElfStringTable(ref.owner().get(sh_link));
		}
		return null;
	}

	public Map<Integer, ElfBlob.ElfSectionHeader> hash(ElfBlob blob, Set<Integer> types) throws IOException {
		ElfBlob.ElfSectionTable sections = blob.sections();
		Map<Integer, ElfBlob.ElfSectionHeader> result = new HashMap<>();

		for (int i = 0; i < sections.size() || (result.size() != types.size()); i++) {
			ElfBlob.ElfSectionHeader hdr = sections.get(i);
			int sh_type = hdr.type();

			if (types.contains(sh_type)) {
				result.put(sh_type, hdr);
			}
		}

		return result;
	}

	public AbiBinaryHashCode hash(ElfBlob blob) throws IOException {
		Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(blob, dyn_types);

		ElfBlob.ElfSectionHeader dynamic = shs.get(SHT_DYNAMIC);
		ElfBlob.ElfSectionHeader dynsym = shs.get(SHT_DYNSYM);

		ElfBlob.ElfStringTable strtab = loadDynstr(dynsym);

		String soname = null;
		if (dynamic != null && strtab != null) {
			soname = extractSoname(blob, strtab, dynamic.offset(), dynamic.size());
		}

		Set<ExportedSymbol> symbols = new LinkedHashSet<>();
		if (dynsym == null) {
			// no exprted symbols
		} else if (strtab != null) {
			visitGlobalOrWeakSymbols(dynsym, sym -> {
				if (sym.shndx() != SHN_UNDEF) {
					String name = strtab.get(sym.name() & 0xFFFFFFFF);
					if (!name.isEmpty()) {
						symbols.add(new ElfExportedSymbol(name, sym.binding()));
					}
				}
			});
		} else {
			// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
			throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
		}

		return new ElfHashCode(soname, symbols);
	}

	public AbiBinaryHashCode hashRel(ElfBlob blob) throws IOException {
		Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(blob, rel_types);

		ElfBlob.ElfSectionHeader symtab = shs.get(SHT_SYMTAB);

		ElfBlob.ElfStringTable strtab = loadDynstr(symtab);

		Set<Object> symbols = new LinkedHashSet<>();
		if (symtab == null) {
			// no import table
		} else if (strtab != null) {
			visitGlobalOrWeakSymbols(symtab, sym -> {
				if (sym.shndx() == SHN_UNDEF) {
					symbols.add(strtab.get(sym.name() & 0xFFFFFFFF));
				}
			});
		} else {
			// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
			throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
		}

		return new ElfImportHashCode(symbols);
	}

	public void visitImports(ElfBlob blob, Consumer<? super Object> visitor) throws IOException {
		Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(blob, rel_types);

		ElfBlob.ElfSectionHeader symtab = shs.get(SHT_SYMTAB);

		ElfBlob.ElfStringTable strtab = loadDynstr(symtab);

		if (symtab == null) {
			// no import table
		} else if (strtab != null) {
			visitGlobalOrWeakSymbols(symtab, sym -> {
				if (sym.shndx() == SHN_UNDEF) {
					String name = strtab.get(sym.name() & 0xFFFFFFFF);
					if (!name.isEmpty()) {
						visitor.accept(name);
					}
				}
			});
		} else {
			// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
			throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
		}
	}

	private String extractSoname(ElfBlob blob, ElfBlob.ElfStringTable strtab, long dynOff, long dynSize) throws IOException {
		int entSize = blob.dt_entsize();
		int count = (int) (dynSize / entSize);

		// Map the dynamic table: it is scanned entry by entry (until DT_NULL/DT_SONAME), so a mapping turns
		// those per-entry reads into memory accesses. Each entry i is at index i * entSize into this mapping.
		MappedByteBuffer dynamic = blob.source.mmap(dynOff, dynSize);
		dynamic.order(blob.order);

		for (int i = 0; i < count; i++) {
			int dyn = i * entSize;
			long tag = blob.d_tag(dynamic, dyn);
			long val = blob.d_val(dynamic, dyn);
			if (tag == DT_NULL) break;
			if (tag == DT_SONAME) {
				return strtab.get(val);
			}
		}
		return null;
	}


	private void visitGlobalOrWeakSymbols(ElfBlob.ElfSectionHeader sh, Consumer<? super ElfBlob.ElfSymbol> visitor) throws IOException {
		ElfBlob.ElfSymbolTable symtab = new ElfBlob.ElfSymbolTable(sh);
		for (ElfBlob.ElfSymbol sym : symtab) { // entry 0 is always STN_UNDEF
			int binding = sym.binding();
			if (binding == STB_GLOBAL || binding == STB_WEAK) {
				visitor.accept(sym);
			}
		}
	}

	public void hash(BSource source, Consumer<? super Object> visitor) throws IOException {
		visitImports(ElfBlob.parse(source), visitor);
	}

	private static final class ElfExportedSymbol extends AbstractMap<String, Object> implements ExportedSymbol {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public ElfExportedSymbol(Object name, int binding) {
			entries.add(new SimpleEntry<>("name", name));
			entries.add(new SimpleEntry<>("binding", binding));
		}

		@Override
		public Object getName() {
			return get("name");
		}

		public int binding() {
			return (int) get("binding");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}

	private static final class ElfHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasExportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public ElfHashCode(String soname, Set<ExportedSymbol> symbols) {
			entries.add(new SimpleEntry<>("soname", soname));
			entries.add(new SimpleEntry<>("symbols", symbols));
		}

		public ElfHashCode(String soname, HashCode hash) {
			entries.add(new SimpleEntry<>("soname", soname));
			entries.add(new SimpleEntry<>("symbols-hash", hash));
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
		public Set<ExportedSymbol> getExportedSymbols() {
			return (Set<ExportedSymbol>) get("symbols");
		}

		@Override
		public HasExportSymbols narrowExports(Set<Object> allImports, Set<Object> unresolved) {
			PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
			for (ExportedSymbol symbol : getExportedSymbols()) {
				if (allImports.contains(symbol.getName())) {
					hasher.putInt((Integer) symbol.getName());
					hasher.putInt(((ElfExportedSymbol) symbol).binding());
					unresolved.remove(symbol.getName());
				}
			}
			return new ElfHashCode((String) get("soname"), hasher.hash());
		}
	}

	private static final class ElfImportHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasImportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		ElfImportHashCode(Set<Object> importedSymbols) {
			entries.add(new SimpleEntry<>("symbols", importedSymbols));
		}

		@Override
		public Type type() {
			return Type.OBJECT_FILE;
		}

		@Override
		public Set<Object> getImportedSymbols() {
			return (Set<Object>) get("symbols");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}
}
