package dev.nokee.nativeplatform.tasks;

import org.gradle.api.model.ObjectFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

final class MachOAbiExtractor {
	private static final int MH_MAGIC = 0xFEEDFACE;
	private static final int MH_CIGAM = 0xCEFAEDFE;
	private static final int MH_MAGIC_64 = 0xFEEDFACF;
	private static final int MH_CIGAM_64 = 0xCFFAEDFE;

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
	private final ObjectFactory objects;

	public MachOAbiExtractor(ObjectFactory objects) {
		this.objects = objects;
	}

	public @Nullable AbiModel extract(FileChannel channel, byte[] header) throws IOException {
		int m = asInt(header, 0);
		if (m == 0xCAFEBABE || m == Integer.reverseBytes(0xCAFEBABE)) {
			return extractFat(channel);
		}
		return extractSlice(channel, 0, header);
	}

	private AbiModel extractFat(FileChannel channel) throws IOException {
		ByteBuffer fatHdr = BinaryUtils.readAt(channel, 0, 8);
		fatHdr.order(ByteOrder.BIG_ENDIAN); // fat binary is always big-endian
		int nfatArch = fatHdr.getInt(4);
		if (nfatArch == 0) {
			return null;
		}
		// fat_arch[0]: cputype(4), cpusubtype(4), offset(4), size(4), align(4)
		ByteBuffer arch0 = BinaryUtils.readAt(channel, 8, 20);
		arch0.order(ByteOrder.BIG_ENDIAN);
		long sliceOffset = arch0.getInt(8) & 0xFFFFFFFFL;
		byte[] sliceHeader = BinaryUtils.readBytes(channel, sliceOffset, 4);
		return extractSlice(channel, sliceOffset, sliceHeader);
	}

	private AbiModel extractSlice(FileChannel channel, long offset, byte[] header) throws IOException {
		int m = asInt(header, 0);
		boolean is64;
		ByteOrder order;
		switch (m) {
			case MH_MAGIC:    is64 = false; order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM:    is64 = false; order = ByteOrder.LITTLE_ENDIAN; break;
			case MH_MAGIC_64: is64 = true;  order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM_64: is64 = true;  order = ByteOrder.LITTLE_ENDIAN; break;
			default: return null;
		}

		int hdrSize = is64 ? 32 : 28;
		ByteBuffer hdr = BinaryUtils.readAt(channel, offset, hdrSize);
		hdr.order(order);

		int filetype = hdr.getInt(12);
		if (filetype != MH_DYLIB && filetype != MH_DYLIB_STUB) {
			return null;
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

		List<ExportedSymbol> symbols = Collections.emptyList();
		if (symoff >= 0 && stroff >= 0 && nsyms > 0) {
			symbols = extractSymbols(channel, order, is64, symoff, nsyms, stroff, strsize,
				hasDysymtab ? iextdefsym : 0,
				hasDysymtab ? nextdefsym : nsyms);
		}

		return objects.newInstance(SharedLibraryAbiModel.class, Optional.ofNullable(installName), symbols);
	}

	private List<ExportedSymbol> extractSymbols(FileChannel channel, ByteOrder order, boolean is64,
		long symoff, int nsyms, long stroff, int strsize,
		int iextdefsym, int nextdefsym) throws IOException {
		int nlistSize = is64 ? 16 : 12;
		int startSym = iextdefsym;
		int endSym = Math.min(iextdefsym + nextdefsym, nsyms);

		ByteBuffer strtab = BinaryUtils.readAt(channel, stroff, strsize);
		List<ExportedSymbol> result = new ArrayList<>();

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

			String name = BinaryUtils.readCString(strtab, strx);
			if (!name.isEmpty()) {
				result.add(objects.newInstance(MachOExportedSymbol.class, name, (nDesc & N_WEAK_DEF) != 0));
			}
		}

		result.sort(Comparator.comparing(thiz -> thiz.getName().get()));
		return Collections.unmodifiableList(result);
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}
}
