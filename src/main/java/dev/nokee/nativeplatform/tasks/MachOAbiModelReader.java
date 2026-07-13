package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class MachOAbiModelReader implements AbiModelReader, AutoCloseable {
	private static final int MH_MAGIC = 0xFEEDFACE;
	private static final int MH_CIGAM = 0xCEFAEDFE;
	private static final int MH_MAGIC_64 = 0xFEEDFACF;
	private static final int MH_CIGAM_64 = 0xCFFAEDFE;
	private static final int FAT_MAGIC = 0xCAFEBABE;

	private static final int MH_DYLIB = 6;
	private static final int MH_DYLIB_STUB = 9;

	private static final int LC_SYMTAB = 0x2;
	private static final int LC_DYSYMTAB = 0xB;
	private static final int LC_ID_DYLIB = 0xD;

	private static final int N_STAB = 0xe0;
	private static final int N_EXT = 0x01;
	private static final int N_TYPE = 0x0e;
	private static final int N_UNDF = 0x00;
	private static final int N_WEAK_DEF = 0x0080;
	private final FileChannel channel;

	MachOAbiModelReader(FileChannel channel) {
		this.channel = channel;
	}

	@Override
	public AbiModel read() throws IOException {
		byte[] header = BinaryUtils.readBytes(channel, 0, 4);
		int m = asInt(header, 0);
		if (!isMachOMagic(m)) {
			throw new IllegalArgumentException("not a Mach-O file");
		}
		if (m == FAT_MAGIC || m == Integer.reverseBytes(FAT_MAGIC)) {
			return extractFat();
		}
		return extractSlice(0, header);
	}

	private static boolean isMachOMagic(int m) {
		return m == MH_MAGIC || m == MH_CIGAM || m == MH_MAGIC_64 || m == MH_CIGAM_64
			|| m == FAT_MAGIC || m == Integer.reverseBytes(FAT_MAGIC);
	}

	private AbiModel extractFat() throws IOException {
		ByteBuffer fatHdr = BinaryUtils.readAt(channel, 0, 8);
		fatHdr.order(ByteOrder.BIG_ENDIAN); // fat binary is always big-endian
		int nfatArch = fatHdr.getInt(4);
		if (nfatArch == 0) {
			throw new NotASharedLibraryException("Mach-O fat binary has no architectures");
		}
		// fat_arch[0]: cputype(4), cpusubtype(4), offset(4), size(4), align(4)
		ByteBuffer arch0 = BinaryUtils.readAt(channel, 8, 20);
		arch0.order(ByteOrder.BIG_ENDIAN);
		long sliceOffset = arch0.getInt(8) & 0xFFFFFFFFL;
		byte[] sliceHeader = BinaryUtils.readBytes(channel, sliceOffset, 4);
		return extractSlice(sliceOffset, sliceHeader);
	}

	private AbiModel extractSlice(long offset, byte[] header) throws IOException {
		int m = asInt(header, 0);
		boolean is64;
		ByteOrder order;
		switch (m) {
			case MH_MAGIC:    is64 = false; order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM:    is64 = false; order = ByteOrder.LITTLE_ENDIAN; break;
			case MH_MAGIC_64: is64 = true;  order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM_64: is64 = true;  order = ByteOrder.LITTLE_ENDIAN; break;
			default: throw new NotASharedLibraryException("unknown Mach-O slice magic");
		}

		int hdrSize = is64 ? 32 : 28;
		ByteBuffer hdr = BinaryUtils.readAt(channel, offset, hdrSize);
		hdr.order(order);

		int filetype = hdr.getInt(12);
		if (filetype != MH_DYLIB && filetype != MH_DYLIB_STUB) {
			throw new NotASharedLibraryException("Mach-O file is not a dylib (filetype=" + filetype + ")");
		}

		int ncmds = hdr.getInt(16);
		long lcOffset = offset + hdrSize;

		String installName = null;
		long symoff = -1, stroff = -1;
		int nsyms = 0, strsize = 0;
		int iextdefsym = 0, nextdefsym = 0;
		boolean hasDysymtab = false;

		// Reuse a single buffer for the per-command header peek instead of allocating one per load command.
		ByteBuffer lc = ByteBuffer.allocate(8);
		lc.order(order);
		for (int i = 0; i < ncmds; i++) {
			BinaryUtils.readInto(channel, lcOffset, lc, 8);
			int cmd = lc.getInt(0);
			int cmdsize = lc.getInt(4);
			if (cmdsize <= 0) break;

			if (cmd == LC_ID_DYLIB) {
				ByteBuffer dylibCmd = BinaryUtils.readAt(channel, lcOffset, cmdsize);
				dylibCmd.order(order);
				int nameOffset = dylibCmd.getInt(8);
				if (nameOffset < cmdsize) {
					installName = BinaryUtils.readCString(dylibCmd, nameOffset);
				}
			} else if (cmd == LC_SYMTAB) {
				ByteBuffer st = BinaryUtils.readAt(channel, lcOffset, 24);
				st.order(order);
				symoff = st.getInt(8) & 0xFFFFFFFFL;
				nsyms = st.getInt(12);
				stroff = st.getInt(16) & 0xFFFFFFFFL;
				strsize = st.getInt(20);
			} else if (cmd == LC_DYSYMTAB) {
				ByteBuffer dst = BinaryUtils.readAt(channel, lcOffset, 24);
				dst.order(order);
				iextdefsym = dst.getInt(16);
				nextdefsym = dst.getInt(20);
				hasDysymtab = true;
			}

			lcOffset += cmdsize;
		}

		Set<HashCode> symbols = Collections.emptySet();
		if (symoff >= 0 && stroff >= 0 && nsyms > 0) {
			symbols = extractSymbols(channel, order, is64, symoff, nsyms, stroff, strsize,
				hasDysymtab ? iextdefsym : 0,
				hasDysymtab ? nextdefsym : nsyms);
		}

		return new SharedLibraryAbiModel(installName, symbols);
	}

	private Set<HashCode> extractSymbols(FileChannel channel, ByteOrder order, boolean is64,
		long symoff, int nsyms, long stroff, int strsize,
		int iextdefsym, int nextdefsym) throws IOException {
		int nlistSize = is64 ? 16 : 12;
		int startSym = iextdefsym;
		int endSym = Math.min(iextdefsym + nextdefsym, nsyms);

		ByteBuffer strtab = BinaryUtils.readAt(channel, stroff, strsize);
		Set<HashCode> result = new LinkedHashSet<>();

		// Reuse a single nlist-sized buffer across all symbols instead of allocating one per entry.
		ByteBuffer sym = ByteBuffer.allocate(nlistSize);
		sym.order(order);
		for (int i = startSym; i < endSym; i++) {
			BinaryUtils.readInto(channel, symoff + (long) i * nlistSize, sym, nlistSize);
			int strx = sym.getInt(0);
			int nType = sym.get(4) & 0xFF;
			int nDesc = sym.getShort(6) & 0xFFFF;

			if ((nType & N_STAB) != 0) continue;
			if ((nType & N_EXT) == 0) continue;
			if ((nType & N_TYPE) == N_UNDF) continue;

			PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
			int length = BinaryUtils.hashCString(hasher, strtab, strx);
			if (length > 0) {
				hasher.putBoolean((nDesc & N_WEAK_DEF) != 0);
				result.add(hasher.hash());
			}
		}

		return Collections.unmodifiableSet(result);
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
