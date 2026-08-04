package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.function.Consumer;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;

// Care was taken to avoid as many condition and allocation as possible
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

	private final ByteBuffer buffer = ByteBuffer.allocate(64); // this buffer gets allocated once

	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		ElfFileChannel elf = newChannel(source);
		int e_type = asUnsigned(buffer.getShort(16));

		if (e_type == ET_DYN) return elf.hash();
		if (e_type == ET_REL) return elf.hashRel();
		throw new IllegalStateException("ELF file is not parsable (e_type=" + e_type + ")");
	}

	private ElfFileChannel newChannel(BSource source) throws IOException {
		// e_ident (first 16 bytes) is format-independent, so read the full 64-bit header size up front:
		// a single read covers both the identification and the rest of the header, and the shorter
		// 32-bit header (52 bytes) fits within these 64 bytes.
		ByteBuffer hdr = BinaryUtils.readInto(source, 0, buffer, 64);
		if (!(hdr.get(EI_MAG0) == ELFMAG0 && hdr.get(EI_MAG1) == ELFMAG1 && hdr.get(EI_MAG2) == ELFMAG2 && hdr.get(EI_MAG3) == ELFMAG3)) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = hdr.get(EI_CLASS) == ELFCLASS64;
		ByteOrder order = hdr.get(EI_DATA) == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		hdr.order(order);

		ElfFileChannel elf = (is64 ? new Elf64FileChannel(source, order) : new Elf32FileChannel(source, order));
		return elf;
	}

	public void hash(BSource source, Consumer<? super Object> visitor) throws IOException {
		newChannel(source).visitImports(visitor);
	}

	private final class Elf64FileChannel extends ElfFileChannel {
		Elf64FileChannel(BSource source, ByteOrder order) {
			super(source, order);
		}

		@Override
		int dt_entsize() {
			return 16;
		}

		@Override
		long d_tag(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off));
		}

		@Override
		long d_val(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 8));
		}

		@Override
		int st_info(ByteBuffer buf, long off) {
			return asUnsigned(buf.get(requireInt(off + 4)));
		}

		@Override
		int st_shndx(ByteBuffer buf, long off) {
			return asUnsigned(buf.getShort(requireInt(off + 6)));
		}

		@Override
		long e_shoff() {
			return buffer.getLong(40);
		}

		@Override
		int e_shentsize() {
			return asUnsigned(buffer.getShort(58));
		}

		@Override
		int e_shnum() {
			return asUnsigned(buffer.getShort(60));
		}

		@Override
		long sh_offset(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 24));
		}

		@Override
		long sh_size(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 32));
		}

		@Override
		int sh_link(ByteBuffer buf, long off) {
			return buf.getInt(requireInt(off + 40));
		}

		@Override
		long sh_entsize(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 56));
		}
	}

	private final class Elf32FileChannel extends ElfFileChannel {
		Elf32FileChannel(BSource source, ByteOrder order) {
			super(source, order);
		}

		@Override
		int dt_entsize() {
			return 8;
		}

		@Override
		long d_tag(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off)));
		}

		@Override
		long d_val(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 4)));
		}


		@Override
		int st_info(ByteBuffer buf, long off) {
			return asUnsigned(buf.get(requireInt(off + 12)));
		}

		@Override
		int st_shndx(ByteBuffer buf, long off) {
			return asUnsigned(buf.getShort(requireInt(off + 14)));
		}

		@Override
		long e_shoff() {
			return asUnsigned(buffer.getInt(32));
		}

		@Override
		int e_shentsize() {
			return asUnsigned(buffer.getShort(46));
		}

		@Override
		int e_shnum() {
			return asUnsigned(buffer.getShort(48));
		}

		@Override
		long sh_offset(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 16)));
		}

		@Override
		long sh_size(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 20)));
		}

		@Override
		int sh_link(ByteBuffer buf, long off) {
			return buf.getInt(requireInt(off + 24));
		}

		@Override
		long sh_entsize(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 36)));
		}
	}

	private static int requireInt(long l) {
		if (l > 0xFFFFFFFFL) throw new IllegalArgumentException();
		return (int) l;
	}

	private static abstract class ElfFileChannel {
		private final BSource source;
		private final ByteOrder order;

		abstract long e_shoff();
		abstract int e_shentsize();
		abstract int e_shnum();

		abstract long sh_offset(ByteBuffer buf, long off);
		abstract long sh_size(ByteBuffer buf, long off);
		abstract int sh_link(ByteBuffer buf, long off);
		abstract long sh_entsize(ByteBuffer buf, long off);

		public void visitImports(Consumer<? super Object> visitor) throws IOException {
			Map<Integer, SectionHeaderRef> shs = hash(rel_types);

			SectionHeaderRef symtab = shs.get(SHT_SYMTAB);

			MappedByteBuffer strtab = loadDynstr(symtab);

			if (symtab == null) {
				// no import table
			} else if (strtab != null) {
				visitGlobalOrWeakSymbols(symtab, sym -> {
					if (sym.shndx() == SHN_UNDEF) {
						visitor.accept(BinaryUtils.readCString(strtab, sym.name() & 0xFFFFFFFF).hashCode());
					}
				});
			} else {
				// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
				throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
			}
		}

		private final class SectionHeaderRef {
			private final ByteBuffer sht;
			private final int sh;

			private SectionHeaderRef(ByteBuffer sht, int sh) {
				this.sht = sht;
				this.sh = sh;
			}

			long offset() { return sh_offset(sht, sh); }
			long size() { return sh_size(sht, sh); }
			int link() { return sh_link(sht, sh); }
			long entsize() { return sh_entsize(sht, sh); }
		}

		protected ElfFileChannel(BSource source, ByteOrder order) {
			this.source = source;
			this.order = order;
		}

		private MappedByteBuffer loadDynstr(SectionHeaderRef ref) throws IOException {
			int sh_link = ref.link();
			if (sh_link >= 0) { // if we overflow, there will be an exception
				int sh = sh_link * e_shentsize();
				return source.mmap(sh_offset(ref.sht, sh), sh_size(ref.sht, sh));
			}
			return null;
		}

		private static final Set<Integer> dyn_types = new HashSet<>();
		private static final Set<Integer> rel_types = Collections.singleton(SHT_SYMTAB);
		static {
			dyn_types.add(SHT_DYNAMIC);
			dyn_types.add(SHT_DYNSYM);
		}

		public Map<Integer, SectionHeaderRef> hash(Set<Integer> types) throws IOException {
			long e_shoff = e_shoff();
			int e_shentsize = e_shentsize();
			int e_shnum = e_shnum();

			if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) {
				// No section headers: this reader resolves exports through them, so we cannot read the ABI.
				throw new UnreadableSharedLibraryException("ELF shared library has no section headers");
			}

			Map<Integer, SectionHeaderRef> result = new HashMap<>();

			ByteBuffer sht = source.mmap(e_shoff, (long) e_shentsize * e_shnum).order(order);
			for (int i = 0; i < e_shnum || (result.size() != types.size()); i++) {
				int sh = i * e_shentsize;
				int sh_type = sht.getInt(sh + 4);

				if (types.contains(sh_type)) {
					result.put(sh_type, new SectionHeaderRef(sht, sh));
				}
			}

			return result;
		}

		public AbiBinaryHashCode hash() throws IOException {
			Map<Integer, SectionHeaderRef> shs = hash(dyn_types);

			SectionHeaderRef dynamic = shs.get(SHT_DYNAMIC);
			SectionHeaderRef dynsym = shs.get(SHT_DYNSYM);

			MappedByteBuffer strtab = loadDynstr(dynsym);

			String soname = null;
			if (dynamic != null && strtab != null) {
				soname = extractSoname(strtab, dynamic.offset(), dynamic.size());
			}

			Set<ExportedSymbol> symbols = new LinkedHashSet<>();
			if (dynsym == null) {
				// no exprted symbols
			} else if (strtab != null) {
				visitGlobalOrWeakSymbols(dynsym, sym -> {
					if (sym.shndx() != SHN_UNDEF) {
						String name = BinaryUtils.readCString(strtab, sym.name() & 0xFFFFFFFF);
						if (!name.isEmpty()) {
							symbols.add(new ElfExportedSymbol(name.hashCode(), sym.binding()));
						}
					}
				});
			} else {
				// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
				throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
			}

			return new ElfHashCode(soname, symbols);
		}

		public AbiBinaryHashCode hashRel() throws IOException {
			Map<Integer, SectionHeaderRef> shs = hash(rel_types);

			SectionHeaderRef symtab = shs.get(SHT_SYMTAB);

			MappedByteBuffer strtab = loadDynstr(symtab);

			Set<Object> symbols = new LinkedHashSet<>();
			if (symtab == null) {
				// no import table
			} else if (strtab != null) {
				visitGlobalOrWeakSymbols(symtab, sym -> {
					if (sym.shndx() == SHN_UNDEF) {
						symbols.add(BinaryUtils.readCString(strtab, sym.name() & 0xFFFFFFFF).hashCode());
					}
				});
			} else {
				// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
				throw new UnreadableSharedLibraryException("ELF shared library .dynsym is unreadable");
			}

			return new ElfImportHashCode(symbols);
		}

		abstract int dt_entsize();
		abstract long d_tag(ByteBuffer buf, long off);
		abstract long d_val(ByteBuffer buf, long off);

		private String extractSoname(MappedByteBuffer strtab, long dynOff, long dynSize) throws IOException {
			int entSize = dt_entsize();
			int count = (int) (dynSize / entSize);

			// Map the dynamic table: it is scanned entry by entry (until DT_NULL/DT_SONAME), so a mapping turns
			// those per-entry reads into memory accesses. Each entry i is at index i * entSize into this mapping.
			MappedByteBuffer dynamic = source.mmap(dynOff, dynSize);
			dynamic.order(order);

			for (int i = 0; i < count; i++) {
				int dyn = i * entSize;
				long tag = d_tag(dynamic, dyn);
				long val = d_val(dynamic, dyn);
				if (tag == DT_NULL) break;
				if (tag == DT_SONAME) {
					return BinaryUtils.readCString(strtab, (int) val, strtab.limit());
				}
			}
			return null;
		}

		int st_name(ByteBuffer buf, long off) {
			return buf.getInt(requireInt(off));
		}

		abstract int st_info(ByteBuffer buf, long off);
		abstract int st_shndx(ByteBuffer buf, long off);

		private final class SymbolRef {
			private final ByteBuffer symtab;
			private long sym;

			private SymbolRef(ByteBuffer symtab) {
				this.symtab = symtab;
			}

			private SymbolRef reset(long sym) {
				this.sym = sym;
				return this;
			}

			public int name() {
				return st_name(symtab, sym);
			}

			public int shndx() {
				return st_shndx(symtab, sym);
			}

			public int binding() {
				return st_info(symtab, sym) >> 4;
			}
		}

		private void visitGlobalOrWeakSymbols(SectionHeaderRef sh, Consumer<? super SymbolRef> visitor) throws IOException {
			long symSize = sh.size();
			long symEntsize = sh.entsize();

			ByteBuffer symtab = source.mmap(sh.offset(), symSize).order(order);
			SymbolRef symbol = new SymbolRef(symtab);

			int count = (int) (symSize / symEntsize);
			for (int i = 1; i < count; i++) { // entry 0 is always STN_UNDEF
				int sym = (int) (i * symEntsize);
				int stInfo = st_info(symtab, sym);
				int binding = stInfo >> 4;
				if (binding == STB_GLOBAL || binding == STB_WEAK) {
					visitor.accept(symbol.reset(sym));
				}
			}
		}
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
