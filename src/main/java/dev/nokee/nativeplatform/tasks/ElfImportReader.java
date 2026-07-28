package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the undefined external symbols of an ELF relocatable object (ET_REL) from its {@code .symtab} /
 * {@code .strtab} — the tables a linker resolves against, distinct from the {@code .dynsym} an
 * {@link ElfBinaryHasher} reads for a shared library's exports. Like the export reader, it maps only the
 * section-header table, symbol table and string table rather than loading the whole image.
 */
final class ElfImportReader implements AbiBinaryHasher, AbiObjectHasher {
	private static final byte ELFMAG0 = 0x7f;
	private static final byte ELFMAG1 = 'E';
	private static final byte ELFMAG2 = 'L';
	private static final byte ELFMAG3 = 'F';

	private static final int EI_CLASS = 4;
	private static final int EI_DATA = 5;
	private static final byte ELFCLASS64 = 2;
	private static final byte ELFDATA2LSB = 1;

	private static final int SHT_SYMTAB = 2;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;

	private final ByteBuffer header = ByteBuffer.allocate(64);

	/**
	 * Reads the imports of the ELF object starting at the beginning of {@code channel}.
	 */
	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		return hash(channel, 0, channel.size());
	}

	/**
	 * Reads the imports of the ELF object image occupying {@code [base, base + size)} of {@code channel} —
	 * {@code base == 0} for a standalone object file, or the member's data offset for a static-archive
	 * member. Only the section-header table, symbol table and string table sub-ranges are mapped.
	 */
	@Override
	public AbiBinaryHashCode hash(FileChannel channel, long base, long size) throws IOException {
		return new ElfImportHashCode(readImports(channel, base, size));
	}

	private Set<Object> readImports(FileChannel channel, long base, long size) throws IOException {
		if (size < 64) {
			throw new IllegalArgumentException("not an ELF file");
		}
		ByteBuffer hdr = BinaryUtils.readInto(channel, base, header, 64);
		if (hdr.get(0) != ELFMAG0 || hdr.get(1) != ELFMAG1 || hdr.get(2) != ELFMAG2 || hdr.get(3) != ELFMAG3) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = hdr.get(EI_CLASS) == ELFCLASS64;
		ByteOrder order = hdr.get(EI_DATA) == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		hdr.order(order);

		long e_shoff = is64 ? hdr.getLong(40) : (hdr.getInt(32) & 0xFFFFFFFFL);
		int e_shentsize = hdr.getShort(is64 ? 58 : 46) & 0xFFFF;
		int e_shnum = hdr.getShort(is64 ? 60 : 48) & 0xFFFF;
		if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) {
			return new LinkedHashSet<>();
		}

		// Map the section header table and find .symtab plus, via its sh_link, the .strtab holding the names.
		MappedByteBuffer sht = channel.map(FileChannel.MapMode.READ_ONLY, base + e_shoff, (long) e_shentsize * e_shnum);
		sht.order(order);
		long symOff = -1, symSize = -1, symEntsize = -1;
		int symLink = -1;
		for (int i = 0; i < e_shnum; i++) {
			int sh = i * e_shentsize;
			if (sht.getInt(sh + 4) == SHT_SYMTAB) {
				symOff = is64 ? sht.getLong(sh + 24) : sht.getInt(sh + 16) & 0xFFFFFFFFL;
				symSize = is64 ? sht.getLong(sh + 32) : sht.getInt(sh + 20) & 0xFFFFFFFFL;
				symLink = is64 ? sht.getInt(sh + 40) : sht.getInt(sh + 24);
				symEntsize = is64 ? sht.getLong(sh + 56) : sht.getInt(sh + 36) & 0xFFFFFFFFL;
				break;
			}
		}
		if (symOff < 0 || symSize <= 0 || symEntsize <= 0 || symLink < 0 || symLink >= e_shnum) {
			return new LinkedHashSet<>();
		}

		int strSh = symLink * e_shentsize;
		long strOff = is64 ? sht.getLong(strSh + 24) : sht.getInt(strSh + 16) & 0xFFFFFFFFL;
		long strSize = is64 ? sht.getLong(strSh + 32) : sht.getInt(strSh + 20) & 0xFFFFFFFFL;
		if (strOff < 0 || strSize <= 0) {
			return new LinkedHashSet<>();
		}

		// Map the symbol table and the string table; names are read at random from the mapped string table.
		MappedByteBuffer symtab = channel.map(FileChannel.MapMode.READ_ONLY, base + symOff, symSize);
		symtab.order(order);
		MappedByteBuffer strtab = channel.map(FileChannel.MapMode.READ_ONLY, base + strOff, strSize);
		int strEnd = strtab.limit();

		Set<Object> result = new LinkedHashSet<>();
		int count = (int) (symSize / symEntsize);
		for (int i = 1; i < count; i++) { // entry 0 is the STN_UNDEF placeholder
			int sym = (int) (i * symEntsize);
			int stName, stInfo, stShndx;
			if (is64) {
				stName = symtab.getInt(sym);
				stInfo = symtab.get(sym + 4) & 0xFF;
				stShndx = symtab.getShort(sym + 6) & 0xFFFF;
			} else {
				stName = symtab.getInt(sym);
				stInfo = symtab.get(sym + 12) & 0xFF;
				stShndx = symtab.getShort(sym + 14) & 0xFFFF;
			}

			int binding = stInfo >> 4;
			// An import is an undefined reference to a global/weak symbol; SHN_COMMON is a definition, not an import.
			if ((binding == STB_GLOBAL || binding == STB_WEAK) && stShndx == SHN_UNDEF) {
				String name = BinaryUtils.readCString(strtab, stName & 0xFFFFFFFF, strEnd);
				if (!name.isEmpty()) {
					result.add(name);
				}
			}
		}
		return result;
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
