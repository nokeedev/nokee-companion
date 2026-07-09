package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

final class ElfAbiModelReader implements AbiModelReader, AutoCloseable {
	private static final int ET_DYN = 3;
	private static final int SHT_DYNAMIC = 6;
	private static final int SHT_DYNSYM = 11;
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;
	private final FileChannel channel;

	ElfAbiModelReader(FileChannel channel) {
		this.channel = channel;
	}

	@Override
	public AbiModel read() throws IOException {
		ByteBuffer ident = BinaryUtils.readAt(channel, 0, 16);
		if (!(ident.get(0) == 0x7f && ident.get(1) == 0x45 && ident.get(2) == 0x4c && ident.get(3) == 0x46)) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = ident.get(4) == 2;
		ByteOrder order = ident.get(5) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

		ByteBuffer hdr = BinaryUtils.readAt(channel, 0, is64 ? 64 : 52);
		hdr.order(order);

		int eType = hdr.getShort(16) & 0xFFFF;
		if (eType != ET_DYN) {
			throw new NotASharedLibraryException("ELF file is not a shared library (e_type=" + eType + ")");
		}

		long shoff = is64 ? hdr.getLong(40) : (hdr.getInt(32) & 0xFFFFFFFFL);
		int shentsize = hdr.getShort(is64 ? 58 : 46) & 0xFFFF;
		int shnum = hdr.getShort(is64 ? 60 : 48) & 0xFFFF;

		if (shoff == 0 || shnum == 0 || shentsize == 0) {
			return new SharedLibraryAbiModel(null, Collections.emptyList());
		}

		ByteBuffer shdrs = BinaryUtils.readAt(channel, shoff, shentsize * shnum);
		shdrs.order(order);

		long dynstrOff = -1, dynstrSize = -1;
		long dynamicOff = -1, dynamicSize = -1;
		long dynsymOff = -1, dynsymSize = -1, dynsymEntsize = -1;
		int dynsymLink = -1;

		for (int i = 0; i < shnum; i++) {
			int base = i * shentsize;
			int shType = shdrs.getInt(base + 4);
			long shOff, shSize, shEntsize;
			int shLink;

			if (is64) {
				shOff = shdrs.getLong(base + 24);
				shSize = shdrs.getLong(base + 32);
				shLink = shdrs.getInt(base + 40);
				shEntsize = shdrs.getLong(base + 56);
			} else {
				shOff = shdrs.getInt(base + 16) & 0xFFFFFFFFL;
				shSize = shdrs.getInt(base + 20) & 0xFFFFFFFFL;
				shLink = shdrs.getInt(base + 24);
				shEntsize = shdrs.getInt(base + 36) & 0xFFFFFFFFL;
			}

			if (shType == SHT_DYNAMIC) {
				dynamicOff = shOff;
				dynamicSize = shSize;
			} else if (shType == SHT_DYNSYM) {
				dynsymOff = shOff;
				dynsymSize = shSize;
				dynsymEntsize = shEntsize;
				dynsymLink = shLink;
			}
		}

		if (dynsymLink >= 0 && dynsymLink < shnum) {
			int base = dynsymLink * shentsize;
			if (is64) {
				dynstrOff = shdrs.getLong(base + 24);
				dynstrSize = shdrs.getLong(base + 32);
			} else {
				dynstrOff = shdrs.getInt(base + 16) & 0xFFFFFFFFL;
				dynstrSize = shdrs.getInt(base + 20) & 0xFFFFFFFFL;
			}
		}

		String soname = null;
		if (dynamicOff >= 0 && dynstrOff >= 0) {
			soname = extractSoname(channel, order, is64, dynamicOff, dynamicSize, dynstrOff, dynstrSize);
		}

		List<ExportedSymbol> symbols = Collections.emptyList();
		if (dynsymOff >= 0 && dynstrOff >= 0 && dynsymEntsize > 0) {
			symbols = extractSymbols(channel, order, is64, dynsymOff, dynsymSize, dynsymEntsize, dynstrOff, dynstrSize);
		}

		return new SharedLibraryAbiModel(soname, symbols);
	}

	private String extractSoname(FileChannel channel, ByteOrder order, boolean is64,
		long dynOff, long dynSize, long dynstrOff, long dynstrSize) throws IOException {
		int entSize = is64 ? 16 : 8;
		int count = (int) (dynSize / entSize);
		ByteBuffer dyn = BinaryUtils.readAt(channel, dynOff, (int) dynSize);
		dyn.order(order);

		for (int i = 0; i < count; i++) {
			int base = i * entSize;
			long tag = is64 ? dyn.getLong(base) : (dyn.getInt(base) & 0xFFFFFFFFL);
			long val = is64 ? dyn.getLong(base + 8) : (dyn.getInt(base + 4) & 0xFFFFFFFFL);
			if (tag == DT_NULL) break;
			if (tag == DT_SONAME) {
				ByteBuffer dynstr = BinaryUtils.readAt(channel, dynstrOff, (int) dynstrSize);
				return BinaryUtils.readCString(dynstr, (int) val);
			}
		}
		return null;
	}

	private List<ExportedSymbol> extractSymbols(FileChannel channel, ByteOrder order, boolean is64,
		long symOff, long symSize, long symEntsize, long strOff, long strSize) throws IOException {
		ByteBuffer syms = BinaryUtils.readAt(channel, symOff, (int) symSize);
		syms.order(order);
		ByteBuffer strtab = BinaryUtils.readAt(channel, strOff, (int) strSize);

		int count = (int) (symSize / symEntsize);
		List<ExportedSymbol> result = new ArrayList<>();

		for (int i = 1; i < count; i++) { // entry 0 is always STN_UNDEF
			int base = (int) (i * symEntsize);
			int stName, stInfo, stShndx;

			if (is64) {
				stName = syms.getInt(base);
				stInfo = syms.get(base + 4) & 0xFF;
				stShndx = syms.getShort(base + 6) & 0xFFFF;
			} else {
				stName = syms.getInt(base);
				stInfo = syms.get(base + 12) & 0xFF;
				stShndx = syms.getShort(base + 14) & 0xFFFF;
			}

			int binding = stInfo >> 4;
			int type = stInfo & 0xF;

			if ((binding == STB_GLOBAL || binding == STB_WEAK) && stShndx != SHN_UNDEF) {
				String name = BinaryUtils.readCString(strtab, stName);
				if (!name.isEmpty()) {
					result.add(new ElfExportedSymbol(name, binding, type));
				}
			}
		}

		result.sort(Comparator.comparing(ExportedSymbol::getName));
		return Collections.unmodifiableList(result);
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	static final class ElfExportedSymbol implements ExportedSymbol {
		private static final long serialVersionUID = 1L;
		private final String name;
		private final int binding;
		private final int type;

		ElfExportedSymbol(String name, int binding, int type) {
			this.name = name;
			this.binding = binding;
			this.type = type;
		}

		@Override
		public String getName() {
			return name;
		}

		int getBinding() {
			return binding;
		}

		int getType() {
			return type;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ElfExportedSymbol that = (ElfExportedSymbol) o;
			return binding == that.binding && type == that.type && name.equals(that.name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, binding, type);
		}

		@Override
		public String toString() {
			return "exported symbol { name: '" + name + "', binding=" + binding + ", type=" + type + '}';
		}
	}
}
