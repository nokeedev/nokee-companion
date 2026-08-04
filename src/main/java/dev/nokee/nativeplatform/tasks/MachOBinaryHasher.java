package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;

/**
 * Reads both sides of a Mach-O image's ABI from its {@code LC_SYMTAB}: the external symbols a dylib
 * <em>defines</em> ({@code MH_DYLIB}, {@code MH_DYLIB_STUB}) and the undefined external symbols an object
 * <em>references</em> — what a linker resolves against. Which side is read follows the image's filetype,
 * so the same walk of the header and load commands serves a shared library and an object file.
 *
 * <p>Reads the image at {@code [base, base + size)} rather than the whole channel, so a standalone object
 * and an archive member are handled the same way. A fat binary is resolved to its first architecture.
 */
final class MachOBinaryHasher implements AbiBinaryHasher, AbiObjectHasher {
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

	/** Reads a whole file, which is expected to be a shared library. */
	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		long sliceOffset = sliceOffsetOf(source);
		if (sliceOffset < 0) {
			throw new NotASharedLibraryException("Mach-O fat binary has no architectures");
		}
		Slice slice = readSlice(source, sliceOffset);
		if (slice == null) {
			throw new NotASharedLibraryException("unknown Mach-O slice magic");
		}
		if (!slice.isDylib()) {
			throw new NotASharedLibraryException("Mach-O file is not a dylib (filetype=" + slice.filetype + ")");
		}
		return exportsOf(source, slice);
	}

//	/**
//	 * Reads one image — a standalone object file ({@code base == 0}) or an archive member — and returns the
//	 * side of its ABI that its filetype calls for: a dylib's exports, anything else's imports.
//	 */
//	@Override
//	public AbiBinaryHashCode hash(BSource source) throws IOException {
//		requireIdentifiable(source.size());
//		long sliceOffset = sliceOffsetOf(source);
//		if (sliceOffset < 0) {
//			return new MachOImportHashCode(new LinkedHashSet<>());
//		}
//		Slice slice = requireSlice(source, sliceOffset);
//		if (slice.isDylib()) {
//			return exportsOf(source, slice);
//		}
//
//		Set<Object> imports = new LinkedHashSet<>();
//		visitImports(source, slice, imports::add);
//		return new MachOImportHashCode(imports);
//	}

	@Override
	public void visitImports(BSource source, Consumer<? super Object> visitor) throws IOException {
		requireIdentifiable(source.size());
		long sliceOffset = sliceOffsetOf(source);
		if (sliceOffset < 0) {
			return;
		}
		visitImports(source, requireSlice(source, sliceOffset), visitor);
	}

	private static void requireIdentifiable(long size) {
		if (size < 4) {
			throw new IllegalArgumentException("not a Mach-O file");
		}
	}

	/**
	 * Resolves the image to read: the one at {@code base}, or the first architecture of a fat binary.
	 * Returns {@code -1} when a fat binary declares no architectures — there is nothing to read, which the
	 * callers report in the terms their own contract calls for.
	 */
	private static long sliceOffsetOf(BSource source) throws IOException {
		int m = asInt(BinaryUtils.readBytes(source, 0, 4), 0);
		if (!isMachOMagic(m)) {
			throw new IllegalArgumentException("not a Mach-O file");
		}
		if (m != FAT_MAGIC && m != Integer.reverseBytes(FAT_MAGIC)) {
			return 0;
		}

		ByteBuffer fatHdr = BinaryUtils.readAt(source, 0, 8);
		fatHdr.order(ByteOrder.BIG_ENDIAN); // a fat header is always big-endian
		if (fatHdr.getInt(4) == 0) {
			return -1;
		}
		// fat_arch[0]: cputype(4), cpusubtype(4), offset(4), size(4), align(4)
		ByteBuffer arch0 = BinaryUtils.readAt(source, 8, 20);
		arch0.order(ByteOrder.BIG_ENDIAN);
		return asUnsigned(arch0.getInt(8));
	}

	private static Slice requireSlice(BSource source, long offset) throws IOException {
		Slice slice = readSlice(source, offset);
		if (slice == null) {
			throw new IllegalArgumentException("unknown Mach-O slice magic");
		}
		return slice;
	}

	/**
	 * Reads a slice's header and walks its load commands once, collecting everything either side of the ABI
	 * needs. Returns {@code null} when the magic is not one of the four Mach-O forms.
	 */
	private static Slice readSlice(BSource source, long offset) throws IOException {
		boolean is64;
		ByteOrder order;
		switch (asInt(BinaryUtils.readBytes(source, offset, 4), 0)) {
			case MH_MAGIC:    is64 = false; order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM:    is64 = false; order = ByteOrder.LITTLE_ENDIAN; break;
			case MH_MAGIC_64: is64 = true;  order = ByteOrder.BIG_ENDIAN;    break;
			case MH_CIGAM_64: is64 = true;  order = ByteOrder.LITTLE_ENDIAN; break;
			default: return null;
		}

		int hdrSize = is64 ? 32 : 28;
		ByteBuffer hdr = BinaryUtils.readAt(source, offset, hdrSize);
		hdr.order(order);

		Slice slice = new Slice(offset, is64, order, hdr.getInt(12));
		int ncmds = hdr.getInt(16);
		long lcOffset = offset + hdrSize;

		// Reuse a single buffer for the per-command header peek instead of allocating one per load command.
		ByteBuffer lc = ByteBuffer.allocate(8);
		lc.order(order);
		for (int i = 0; i < ncmds; i++) {
			BinaryUtils.readInto(source, lcOffset, lc, 8);
			int cmd = lc.getInt(0);
			int cmdsize = lc.getInt(4);
			if (cmdsize <= 0) break;

			if (cmd == LC_ID_DYLIB) {
				ByteBuffer dylibCmd = BinaryUtils.readAt(source, lcOffset, cmdsize);
				dylibCmd.order(order);
				int nameOffset = dylibCmd.getInt(8);
				if (nameOffset < cmdsize) {
					slice.installName = BinaryUtils.readCString(dylibCmd, nameOffset);
				}
			} else if (cmd == LC_SYMTAB) {
				ByteBuffer st = BinaryUtils.readAt(source, lcOffset, 24);
				st.order(order);
				// Table offsets in a load command are relative to the Mach-O image (the slice) start.
				slice.symoff = offset + asUnsigned(st.getInt(8));
				slice.nsyms = st.getInt(12);
				slice.stroff = offset + asUnsigned(st.getInt(16));
				slice.strsize = st.getInt(20);
				slice.hasSymtab = true;
			} else if (cmd == LC_DYSYMTAB) {
				ByteBuffer dst = BinaryUtils.readAt(source, lcOffset, 24);
				dst.order(order);
				slice.iextdefsym = dst.getInt(16);
				slice.nextdefsym = dst.getInt(20);
				slice.hasDysymtab = true;
			}

			lcOffset += cmdsize;
		}

		return slice;
	}

	/** Collects the external symbols the dylib defines — the exports a dependent link resolves against. */
	private static AbiBinaryHashCode exportsOf(BSource source, Slice slice) throws IOException {
		if (!slice.hasSymtab || slice.nsyms <= 0) {
			// No symbol table to read: we cannot determine the dylib's exports.
			throw new UnreadableSharedLibraryException("Mach-O dylib has no readable symbol table");
		}

		Set<ExportedSymbol> symbols = new LinkedHashSet<>();

		// LC_DYSYMTAB groups the externally defined symbols into one run; without it, scan them all.
		int start = slice.hasDysymtab ? slice.iextdefsym : 0;
		int end = slice.hasDysymtab ? Math.min(slice.iextdefsym + slice.nextdefsym, slice.nsyms) : slice.nsyms;

		int nlistSize = slice.nlistSize();
		long strEnd = slice.strEnd();
		// Reuse a single nlist-sized buffer across all symbols instead of allocating one per entry.
		ByteBuffer sym = ByteBuffer.allocate(nlistSize);
		sym.order(slice.order);
		for (int i = start; i < end; i++) {
			BinaryUtils.readInto(source, slice.symoff + (long) i * nlistSize, sym, nlistSize);
			int strx = sym.getInt(0);
			int nType = asUnsigned(sym.get(4));
			int nDesc = asUnsigned(sym.getShort(6));

			if ((nType & N_STAB) != 0) continue;         // debug symbol
			if ((nType & N_EXT) == 0) continue;          // not external
			if ((nType & N_TYPE) == N_UNDF) continue;    // referenced here, not defined

			// Read each name on demand from the string table instead of loading the whole table.
			String name = BinaryUtils.readCStringAt(source, slice.stroff + asUnsigned(strx), strEnd);
			if (!name.isEmpty()) {
				symbols.add(new MachOExportedSymbol(name, (nDesc & N_WEAK_DEF) != 0));
			}
		}

		return new MachOHashCode(slice.installName, symbols);
	}

	/** Visits the undefined external symbols the image references — the imports a link must resolve. */
	private static void visitImports(BSource source, Slice slice, Consumer<? super Object> visitor) throws IOException {
		if (!slice.hasSymtab || slice.nsyms <= 0) {
			return; // no imports
		}

		int nlistSize = slice.nlistSize();
		long strEnd = slice.strEnd();
		ByteBuffer sym = ByteBuffer.allocate(nlistSize);
		sym.order(slice.order);
		for (int i = 0; i < slice.nsyms; i++) {
			BinaryUtils.readInto(source, slice.symoff + (long) i * nlistSize, sym, nlistSize);
			int strx = sym.getInt(0);
			int nType = asUnsigned(sym.get(4));
			long nValue = slice.is64 ? sym.getLong(8) : asUnsigned(sym.getInt(8));

			if ((nType & N_STAB) != 0) continue;         // debug symbol
			if ((nType & N_EXT) == 0) continue;          // not external
			if ((nType & N_TYPE) != N_UNDF) continue;    // defined here, not an import
			if (nValue != 0) continue;                   // common symbol (tentative definition), not an import
			if (strx == 0) continue;

			String name = BinaryUtils.readCStringAt(source, slice.stroff + asUnsigned(strx), strEnd);
			if (!name.isEmpty()) {
				visitor.accept(name);
			}
		}
	}

	/** One Mach-O image: its header facts plus what its load commands pointed at. */
	private static final class Slice {
		private final long offset;
		private final boolean is64;
		private final ByteOrder order;
		private final int filetype;

		private String installName;
		private boolean hasSymtab;
		private long symoff = -1, stroff = -1;
		private int nsyms, strsize;
		private boolean hasDysymtab;
		private int iextdefsym, nextdefsym;

		private Slice(long offset, boolean is64, ByteOrder order, int filetype) {
			this.offset = offset;
			this.is64 = is64;
			this.order = order;
			this.filetype = filetype;
		}

		private boolean isDylib() {
			return filetype == MH_DYLIB || filetype == MH_DYLIB_STUB;
		}

		private int nlistSize() {
			return is64 ? 16 : 12;
		}

		private long strEnd() {
			return stroff + asUnsigned(strsize);
		}
	}

	private static boolean isMachOMagic(int m) {
		return m == MH_MAGIC || m == MH_CIGAM || m == MH_MAGIC_64 || m == MH_CIGAM_64
			|| m == FAT_MAGIC || m == Integer.reverseBytes(FAT_MAGIC);
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
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
