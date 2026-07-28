package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the undefined external symbols of a Mach-O object ({@code MH_OBJECT}) from its {@code LC_SYMTAB} —
 * the references a linker resolves against, the import-side counterpart of {@link MachOBinaryHasher}. Reads
 * the object image at {@code [base, base + size)} so a standalone object and an archive member are handled
 * the same way.
 */
final class MachOImportReader implements AbiBinaryHasher, AbiObjectHasher {
	private static final int MH_MAGIC = 0xFEEDFACE;
	private static final int MH_CIGAM = 0xCEFAEDFE;
	private static final int MH_MAGIC_64 = 0xFEEDFACF;
	private static final int MH_CIGAM_64 = 0xCFFAEDFE;
	private static final int FAT_MAGIC = 0xCAFEBABE;

	private static final int LC_SYMTAB = 0x2;

	private static final int N_STAB = 0xe0;
	private static final int N_EXT = 0x01;
	private static final int N_TYPE = 0x0e;
	private static final int N_UNDF = 0x00;

	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		return hash(channel, 0, channel.size());
	}

	@Override
	public AbiBinaryHashCode hash(FileChannel channel, long base, long size) throws IOException {
		if (size < 4) {
			throw new IllegalArgumentException("not a Mach-O file");
		}
		byte[] header = BinaryUtils.readBytes(channel, base, 4);
		int m = asInt(header, 0);
		if (!isMachOMagic(m)) {
			throw new IllegalArgumentException("not a Mach-O file");
		}

		long sliceOffset = base;
		int sliceMagic = m;
		if (m == FAT_MAGIC || m == Integer.reverseBytes(FAT_MAGIC)) {
			ByteBuffer fatHdr = BinaryUtils.readAt(channel, base, 8);
			fatHdr.order(ByteOrder.BIG_ENDIAN); // fat header is always big-endian
			int nfatArch = fatHdr.getInt(4);
			if (nfatArch == 0) {
				return new MachOImportHashCode(new LinkedHashSet<>());
			}
			ByteBuffer arch0 = BinaryUtils.readAt(channel, base + 8, 20);
			arch0.order(ByteOrder.BIG_ENDIAN);
			sliceOffset = base + (arch0.getInt(8) & 0xFFFFFFFFL);
			sliceMagic = asInt(BinaryUtils.readBytes(channel, sliceOffset, 4), 0);
		}

		return new MachOImportHashCode(readImports(channel, sliceOffset, sliceMagic));
	}

	private Set<Object> readImports(FileChannel channel, long offset, int m) throws IOException {
		boolean is64;
		ByteOrder order;
		switch (m) {
			case MH_MAGIC:    is64 = false; order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM:    is64 = false; order = ByteOrder.LITTLE_ENDIAN; break;
			case MH_MAGIC_64: is64 = true;  order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM_64: is64 = true;  order = ByteOrder.LITTLE_ENDIAN; break;
			default: throw new IllegalArgumentException("unknown Mach-O slice magic");
		}

		int hdrSize = is64 ? 32 : 28;
		ByteBuffer hdr = BinaryUtils.readAt(channel, offset, hdrSize);
		hdr.order(order);
		int ncmds = hdr.getInt(16);
		long lcOffset = offset + hdrSize;

		// Symbol/string table offsets in a load command are relative to the Mach-O image (the slice) start.
		long symoff = -1, stroff = -1;
		int nsyms = 0, strsize = 0;
		ByteBuffer lc = ByteBuffer.allocate(8);
		lc.order(order);
		for (int i = 0; i < ncmds; i++) {
			BinaryUtils.readInto(channel, lcOffset, lc, 8);
			int cmd = lc.getInt(0);
			int cmdsize = lc.getInt(4);
			if (cmdsize <= 0) break;

			if (cmd == LC_SYMTAB) {
				ByteBuffer st = BinaryUtils.readAt(channel, lcOffset, 24);
				st.order(order);
				symoff = offset + (st.getInt(8) & 0xFFFFFFFFL);
				nsyms = st.getInt(12);
				stroff = offset + (st.getInt(16) & 0xFFFFFFFFL);
				strsize = st.getInt(20);
			}

			lcOffset += cmdsize;
		}

		Set<Object> imports = new LinkedHashSet<>();
		if (symoff < 0 || stroff < 0 || nsyms <= 0) {
			return imports;
		}

		int nlistSize = is64 ? 16 : 12;
		long strEnd = stroff + (strsize & 0xFFFFFFFFL);
		ByteBuffer sym = ByteBuffer.allocate(nlistSize);
		sym.order(order);
		for (int i = 0; i < nsyms; i++) {
			BinaryUtils.readInto(channel, symoff + (long) i * nlistSize, sym, nlistSize);
			int strx = sym.getInt(0);
			int nType = sym.get(4) & 0xFF;
			long nValue = is64 ? sym.getLong(8) : (sym.getInt(8) & 0xFFFFFFFFL);

			if ((nType & N_STAB) != 0) continue;         // debug symbol
			if ((nType & N_EXT) == 0) continue;          // not external
			if ((nType & N_TYPE) != N_UNDF) continue;    // defined here, not an import
			if (nValue != 0) continue;                   // common symbol (tentative definition), not an import
			if (strx == 0) continue;

			String name = BinaryUtils.readCStringAt(channel, stroff + (strx & 0xFFFFFFFFL), strEnd);
			if (!name.isEmpty()) {
				imports.add(name);
			}
		}
		return imports;
	}

	private static boolean isMachOMagic(int m) {
		return m == MH_MAGIC || m == MH_CIGAM || m == MH_MAGIC_64 || m == MH_CIGAM_64
			|| m == FAT_MAGIC || m == Integer.reverseBytes(FAT_MAGIC);
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
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
