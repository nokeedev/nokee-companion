package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;

final class ElfBinaryHasher implements AbiBinaryHasher {
	// for e_ident
	private static final int EI_MAG0 = 0; // index in e_ident array
	private static final int EI_MAG1 = 1; // index in e_ident array
	private static final int EI_MAG2 = 2; // index in e_ident array
	private static final int EI_MAG3 = 3; // index in e_ident array
	private static final int EI_CLASS = 4; // index in e_ident array
	private static final int EI_DATA = 5; // index in e_ident array
	private static final int EI_NIDENT = 16; // size of e_ident array

	private static final byte ELFMAG0 = 0x7f; // required value at e_ident[EI_MAG0]
	private static final byte ELFMAG1 = 'E'; // required value at e_ident[EI_MAG1]
	private static final byte ELFMAG2 = 'L'; // required value at e_ident[EI_MAG2]
	private static final byte ELFMAG3 = 'F'; // required value at e_ident[EI_MAG3]

	private static final byte ELFCLASS64 = 2; // a value of e_ident[EI_CLASS]

	private static final byte ELFDATA2LSB = 1; // little endian value of e_ident[EI_DATA]

	private static final int ET_DYN = 3; // for e_type
	private static final int SHT_DYNAMIC = 6;
	private static final int SHT_DYNSYM = 11;
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;

	private final ByteBuffer buffer = ByteBuffer.allocate(64); // this buffer gets allocated once

	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		// e_ident (first 16 bytes) is format-independent, so read the full 64-bit header size up front:
		// a single read covers both the identification and the rest of the header, and the shorter
		// 32-bit header (52 bytes) fits within these 64 bytes.
		ByteBuffer hdr = BinaryUtils.readInto(channel, 0, buffer, 64);
		if (!(hdr.get(EI_MAG0) == ELFMAG0 && hdr.get(EI_MAG1) == ELFMAG1 && hdr.get(EI_MAG2) == ELFMAG2 && hdr.get(EI_MAG3) == ELFMAG3)) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = hdr.get(EI_CLASS) == ELFCLASS64;
		ByteOrder order = hdr.get(EI_DATA) == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		hdr.order(order);

		int e_type = asUnsigned(hdr.getShort(16));
		if (e_type != ET_DYN) {
			throw new NotASharedLibraryException("ELF file is not a shared library (e_type=" + e_type + ")");
		}

		long e_shoff = is64 ? hdr.getLong(40) : asUnsigned(hdr.getInt(32));
		int e_shentsize = asUnsigned(hdr.getShort(is64 ? 58 : 46));
		int e_shnum = asUnsigned(hdr.getShort(is64 ? 60 : 48));

		if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) {
			// No section headers: this reader resolves exports through them, so we cannot read the ABI.
			throw new UnreadableSharedLibraryException("ELF shared library has no section headers");
		}

		long dynstrOff = -1, dynstrSize = -1;
		long dynamicOff = -1, dynamicSize = -1;
		long dynsymOff = -1, dynsymSize = -1, dynsymEntsize = -1;
		int dynsymLink = -1;

		// Map the section header table: it is scanned in full (every entry, plus a lookup of the dynstr
		// section), so a mapping turns those per-entry reads into memory accesses. Each entry i is at index
		// i * e_shentsize into this mapping.
		MappedByteBuffer sht = channel.map(FileChannel.MapMode.READ_ONLY, e_shoff, (long) e_shentsize * e_shnum);
		sht.order(order);
		for (int i = 0; i < e_shnum; i++) {
			int sh = i * e_shentsize;
			int sh_type = sht.getInt(sh + 4);
			long sh_offset = is64 ? sht.getLong(sh + 24) : asUnsigned(sht.getInt(sh + 16));
			long sh_size = is64 ? sht.getLong(sh + 32) : asUnsigned(sht.getInt(sh + 20));
			int sh_link = is64 ? sht.getInt(sh + 40) : sht.getInt(sh + 24);
			long sh_entsize = is64 ? sht.getLong(sh + 56) : asUnsigned(sht.getInt(sh + 36));

			if (sh_type == SHT_DYNAMIC) { // only one section can exists
				dynamicOff = sh_offset;
				dynamicSize = sh_size;
			} else if (sh_type == SHT_DYNSYM) {
				dynsymOff = sh_offset;
				dynsymSize = sh_size;
				dynsymEntsize = sh_entsize;
				dynsymLink = sh_link;
			}
		}

		if (dynsymLink >= 0 && dynsymLink < e_shnum) {
			int sh = dynsymLink * e_shentsize;
			if (is64) {
				dynstrOff = sht.getLong(sh + 24);
				dynstrSize = sht.getLong(sh + 32);
			} else {
				dynstrOff = asUnsigned(sht.getInt(sh + 16));
				dynstrSize = asUnsigned(sht.getInt(sh + 20));
			}
		}

		// Map just the string table: it is the one section read at random (a lookup per exported symbol
		// name), so a mapping turns those scattered reads into memory accesses paged in on demand, while
		// the header, section headers and symbol entries keep streaming through the channel. Offsets into
		// the table (st_name, DT_SONAME's value) are relative to its start, i.e. indices into this mapping.
		MappedByteBuffer strtab = null;
		if (dynstrOff >= 0 && dynstrSize > 0) {
			strtab = channel.map(FileChannel.MapMode.READ_ONLY, dynstrOff, dynstrSize);
		}

		String soname = null;
		if (dynamicOff >= 0 && strtab != null) {
			soname = extractSoname(channel, strtab, order, is64, dynamicOff, dynamicSize);
		}

		Set<ExportedSymbol> symbols;
		if (dynsymOff < 0) {
			symbols = Collections.emptySet(); // no .dynsym: the library exports nothing dynamically
		} else if (strtab != null && dynsymEntsize > 0) {
			symbols = extractSymbols(channel, strtab, order, is64, dynsymOff, dynsymSize, dynsymEntsize);
		} else {
			// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
			throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
		}

		return new ElfHashCode(soname, symbols);
	}

	private String extractSoname(FileChannel channel, MappedByteBuffer strtab, ByteOrder order, boolean is64,
		long dynOff, long dynSize) throws IOException {
		int entSize = is64 ? 16 : 8;
		int count = (int) (dynSize / entSize);

		// Map the dynamic table: it is scanned entry by entry (until DT_NULL/DT_SONAME), so a mapping turns
		// those per-entry reads into memory accesses. Each entry i is at index i * entSize into this mapping.
		MappedByteBuffer dynamic = channel.map(FileChannel.MapMode.READ_ONLY, dynOff, dynSize);
		dynamic.order(order);

		for (int i = 0; i < count; i++) {
			int dyn = i * entSize;
			long tag = is64 ? dynamic.getLong(dyn) : asUnsigned(dynamic.getInt(dyn));
			long val = is64 ? dynamic.getLong(dyn + 8) : asUnsigned(dynamic.getInt(dyn + 4));
			if (tag == DT_NULL) break;
			if (tag == DT_SONAME) {
				return BinaryUtils.readCString(strtab, (int) val, strtab.limit());
			}
		}
		return null;
	}

	private Set<ExportedSymbol> extractSymbols(FileChannel channel, MappedByteBuffer strtab, ByteOrder order, boolean is64,
		long symOff, long symSize, long symEntsize) throws IOException {

		// Map the symbol table: it is scanned in full (one entry per symbol), so a mapping turns those
		// per-entry reads into memory accesses. Each entry i is at index i * symEntsize into this mapping.
		MappedByteBuffer symtab = channel.map(FileChannel.MapMode.READ_ONLY, symOff, symSize);
		symtab.order(order);

		// Each symbol name is read at random from the mapped string table (indexed by st_name).
		int strEnd = strtab.limit();

		int count = (int) (symSize / symEntsize);
		Set<ExportedSymbol> result = new LinkedHashSet<>();

		for (int i = 1; i < count; i++) { // entry 0 is always STN_UNDEF
			int sym = (int) (i * symEntsize);
			int stName, stInfo, stShndx;

			if (is64) {
				stName = symtab.getInt(sym);
				stInfo = asUnsigned(symtab.get(sym + 4));
				stShndx = asUnsigned(symtab.getShort(sym + 6));
			} else {
				stName = symtab.getInt(sym);
				stInfo = asUnsigned(symtab.get(sym + 12));
				stShndx = asUnsigned(symtab.getShort(sym + 14));
			}

			int binding = stInfo >> 4;

			if ((binding == STB_GLOBAL || binding == STB_WEAK) && stShndx != SHN_UNDEF) {
				String name = BinaryUtils.readCString(strtab, stName & 0xFFFFFFFF, strEnd);
				if (!name.isEmpty()) {
					result.add(new ElfExportedSymbol(name, binding));
				}
			}
		}

		return result;
	}

	private static final class ElfExportedSymbol extends AbstractMap<String, Object> implements ExportedSymbol {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public ElfExportedSymbol(String name, int binding) {
			entries.add(new SimpleEntry<>("name", name));
			entries.add(new SimpleEntry<>("binding", binding));
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

	private static final class ElfHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasExportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public ElfHashCode(String soname, Set<ExportedSymbol> symbols) {
			entries.add(new SimpleEntry<>("soname", soname));
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
			return new ElfHashCode((String) get("soname"), retained);
		}
	}
}
