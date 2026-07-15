package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

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

	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		// e_ident (first 16 bytes) is format-independent, so read the full 64-bit header size up front:
		// a single read covers both the identification and the rest of the header, and the shorter
		// 32-bit header (52 bytes) fits within these 64 bytes.
		ByteBuffer hdr = BinaryUtils.readAt(channel, 0, 64);
		if (!(hdr.get(EI_MAG0) == ELFMAG0 && hdr.get(EI_MAG1) == ELFMAG1 && hdr.get(EI_MAG2) == ELFMAG2 && hdr.get(EI_MAG3) == ELFMAG3)) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = hdr.get(EI_CLASS) == ELFCLASS64;
		ByteOrder order = hdr.get(EI_DATA) == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		hdr.order(order);

		int e_type = hdr.getShort(16) & 0xFFFF;
		if (e_type != ET_DYN) {
			throw new NotASharedLibraryException("ELF file is not a shared library (e_type=" + e_type + ")");
		}

		long e_shoff = is64 ? hdr.getLong(40) : (hdr.getInt(32) & 0xFFFFFFFFL);
		int e_shentsize = hdr.getShort(is64 ? 58 : 46) & 0xFFFF;
		int e_shnum = hdr.getShort(is64 ? 60 : 48) & 0xFFFF;

		if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) {
			return new ElfHashCode(null, null);
		}

		long dynstrOff = -1, dynstrSize = -1;
		long dynamicOff = -1, dynamicSize = -1;
		long dynsymOff = -1, dynsymSize = -1, dynsymEntsize = -1;
		int dynsymLink = -1;

		// Scan the section header table one entry at a time, reusing a single entry-sized buffer
		// instead of holding the whole table (shentsize * shnum bytes) in memory.
		ByteBuffer sh = ByteBuffer.allocate(e_shentsize);
		sh.order(order);
		for (int i = 0; i < e_shnum; i++) {
			BinaryUtils.readInto(channel, e_shoff + (long) i * e_shentsize, sh, e_shentsize);
			int sh_type = sh.getInt(4);
			long sh_offset = is64 ? sh.getLong(24) : sh.getInt(16) & 0xFFFFFFFFL;
			long sh_size = is64 ? sh.getLong(32) : sh.getInt(20) & 0xFFFFFFFFL;
			int sh_link = is64 ? sh.getInt(40) : sh.getInt(24);
			long sh_entsize = is64 ? sh.getLong(56) : sh.getInt(36) & 0xFFFFFFFFL;

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
			BinaryUtils.readInto(channel, e_shoff + (long) dynsymLink * e_shentsize, sh, e_shentsize);
			if (is64) {
				dynstrOff = sh.getLong(24);
				dynstrSize = sh.getLong(32);
			} else {
				dynstrOff = sh.getInt(16) & 0xFFFFFFFFL;
				dynstrSize = sh.getInt(20) & 0xFFFFFFFFL;
			}
		}

		String soname = null;
		if (dynamicOff >= 0 && dynstrOff >= 0) {
			soname = extractSoname(channel, order, is64, dynamicOff, dynamicSize, dynstrOff, dynstrSize);
		}

		HashCode symbols = null;
		if (dynsymOff >= 0 && dynstrOff >= 0 && dynsymEntsize > 0) {
			symbols = extractSymbols(channel, order, is64, dynsymOff, dynsymSize, dynsymEntsize, dynstrOff, dynstrSize);
		}

		return new ElfHashCode(soname, symbols);
	}

	private String extractSoname(FileChannel channel, ByteOrder order, boolean is64,
		long dynOff, long dynSize, long dynstrOff, long dynstrSize) throws IOException {
		int entSize = is64 ? 16 : 8;
		int count = (int) (dynSize / entSize);

		// Scan the dynamic table one entry at a time, reusing a single entry-sized buffer instead of
		// holding the whole section in memory.
		ByteBuffer dyn = ByteBuffer.allocate(entSize);
		dyn.order(order);

		for (int i = 0; i < count; i++) {
			BinaryUtils.readInto(channel, dynOff + (long) i * entSize, dyn, entSize);
			long tag = is64 ? dyn.getLong(0) : (dyn.getInt(0) & 0xFFFFFFFFL);
			long val = is64 ? dyn.getLong(8) : (dyn.getInt(4) & 0xFFFFFFFFL);
			if (tag == DT_NULL) break;
			if (tag == DT_SONAME) {
				return BinaryUtils.readCStringAt(channel, dynstrOff + val, dynstrOff + dynstrSize);
			}
		}
		return null;
	}

	private HashCode extractSymbols(FileChannel channel, ByteOrder order, boolean is64,
		long symOff, long symSize, long symEntsize, long strOff, long strSize) throws IOException {
		PrimitiveHasher hasher = Hashing.newPrimitiveHasher();

		// Scan the symbol table one entry at a time, reusing a single entry-sized buffer instead of
		// holding the whole table (symEntsize * count bytes) in memory.
		ByteBuffer sym = ByteBuffer.allocate((int) symEntsize);
		sym.order(order);

		// Read each symbol name on demand from the string table instead of loading the whole table.
		long strEnd = strOff + strSize;
		ByteBuffer nameBuf = ByteBuffer.allocate(256);

		int count = (int) (symSize / symEntsize);

		int size = 0;
		for (int i = 1; i < count; i++) { // entry 0 is always STN_UNDEF
			BinaryUtils.readInto(channel, symOff + (long) i * symEntsize, sym, (int) symEntsize);
			int stName, stInfo, stShndx;

			if (is64) {
				stName = sym.getInt(0);
				stInfo = sym.get(4) & 0xFF;
				stShndx = sym.getShort(6) & 0xFFFF;
			} else {
				stName = sym.getInt(0);
				stInfo = sym.get(12) & 0xFF;
				stShndx = sym.getShort(14) & 0xFFFF;
			}

			int binding = stInfo >> 4;

			if ((binding == STB_GLOBAL || binding == STB_WEAK) && stShndx != SHN_UNDEF) {
				int length = BinaryUtils.hashCStringAt(hasher, channel, nameBuf, strOff + (stName & 0xFFFFFFFFL), strEnd);
				if (length > 0) {
					hasher.putInt(binding);
					size++;
				}
			}
		}

		if (size > 0) {
			return hasher.hash();
		}
		return null;
	}

	private static final class ElfHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public ElfHashCode(String soname, HashCode symbols) {
			entries.add(new SimpleEntry<>("soname", soname));
			entries.add(new SimpleEntry<>("symbols", symbols));
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}

		@Override
		public HashCode getExportedSymbols() {
			return (HashCode) get("symbols");
		}
	}
}
