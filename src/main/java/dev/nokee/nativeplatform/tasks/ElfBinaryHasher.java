package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.function.Consumer;

// Care was taken to avoid as many condition and allocation as possible
final class ElfBinaryHasher {
	private static final int SHT_SYMTAB = 2; // for sh_type
	private static final int SHT_DYNAMIC = 6; // for sh_type
	private static final int SHT_DYNSYM = 11; // for sh_type
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;

	private static final Set<Integer> dyn_types = new HashSet<>();
	private static final Set<Integer> rel_types = Collections.singleton(SHT_SYMTAB);
	static {
		dyn_types.add(SHT_DYNAMIC);
		dyn_types.add(SHT_DYNSYM);
	}

	private ElfBlob.ElfStringTable loadDynstr(ElfBlob.ElfSectionHeader ref) {
		int sh_link = ref.link();
		if (sh_link >= 0) { // if we overflow, there will be an exception
			return new ElfBlob.ElfStringTable(ref.owner().get(sh_link));
		}
		return null;
	}

	public Map<Integer, ElfBlob.ElfSectionHeader> hash(ElfBlob blob, Set<Integer> types) {
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

	public void visitImports(ElfBlob blob, Consumer<? super String> visitor) {
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
			throw new RuntimeException("ELF shared library .dynsym is unreadable");
		}
	}

	public interface SonameAndExportVisitor {
		void visitSoname(String soname);
		void visitExport(String name, int binding);
	}

	public void visitSharedLib(ElfBlob blob, SonameAndExportVisitor visitor) throws IOException {
		Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(blob, dyn_types);

		ElfBlob.ElfSectionHeader dynamic = shs.get(SHT_DYNAMIC);
		ElfBlob.ElfSectionHeader dynsym = shs.get(SHT_DYNSYM);

		ElfBlob.ElfStringTable strtab = loadDynstr(dynsym);

		if (dynamic != null && strtab != null) {
			visitor.visitSoname(extractSoname(blob, strtab, dynamic.offset(), dynamic.size()));
		}

		if (dynsym == null) {
			// no exprted symbols
		} else if (strtab != null) {
			visitGlobalOrWeakSymbols(dynsym, sym -> {
				if (sym.shndx() != SHN_UNDEF) {
					String name = strtab.get(sym.name() & 0xFFFFFFFF);
					if (!name.isEmpty()) {
						visitor.visitExport(name, sym.binding());
					}
				}
			});
		} else {
			// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
			throw new RuntimeException("ELF shared library .dynsym is unreadable");
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


	private void visitGlobalOrWeakSymbols(ElfBlob.ElfSectionHeader sh, Consumer<? super ElfBlob.ElfSymbol> visitor) {
		ElfBlob.ElfSymbolTable symtab = new ElfBlob.ElfSymbolTable(sh);
		for (ElfBlob.ElfSymbol sym : symtab) { // entry 0 is always STN_UNDEF
			int binding = sym.binding();
			if (binding == STB_GLOBAL || binding == STB_WEAK) {
				visitor.accept(sym);
			}
		}
	}
}
