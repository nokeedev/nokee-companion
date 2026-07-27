package dev.nokee.nativeplatform.tasks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.Iterator;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;
import static dev.nokee.nativeplatform.tasks.BinaryUtils.requireInt;

// Care was taken to avoid as many condition and allocation as possible
// TODO: implements BinaryBlob common interface
// TODO: Prevent ElfBlob from being extended outside of this class via correct visibility of the constructor
abstract class ElfBlob {
	// for e_ident
	private static final int EI_MAG0 = 0; // index in e_ident array
	private static final int EI_MAG1 = 1; // index in e_ident array
	private static final int EI_MAG2 = 2; // index in e_ident array
	private static final int EI_MAG3 = 3; // index in e_ident array
	private static final int EI_CLASS = 4; // index in e_ident array
	private static final int EI_DATA = 5; // index in e_ident array
	public static final int EI_OSABI = 7;
	public static final int EI_ABIVERSION = 8;
	private static final int EI_NIDENT = 16; // size of e_ident array

	private static final byte ELFMAG0 = 0x7f; // required value at e_ident[EI_MAG0]
	private static final byte ELFMAG1 = 'E'; // required value at e_ident[EI_MAG1]
	private static final byte ELFMAG2 = 'L'; // required value at e_ident[EI_MAG2]
	private static final byte ELFMAG3 = 'F'; // required value at e_ident[EI_MAG3]

	private static final byte ELFCLASS64 = 2; // a value of e_ident[EI_CLASS]

	private static final byte ELFDATA2LSB = 1; // little endian value of e_ident[EI_DATA]

	public static final int ET_REL = 1; // for e_type
	public static final int ET_DYN = 3; // for e_type
	private static final int SHT_SYMTAB = 2; // for sh_type
	private static final int SHT_DYNAMIC = 6; // for sh_type
	private static final int SHT_DYNSYM = 11; // for sh_type
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	public static final int STT_NOTYPE = 0; // low nibble of st_info
	public static final int STT_OBJECT = 1; // low nibble of st_info
	public static final int STT_TLS = 6; // low nibble of st_info
	private static final int SHN_UNDEF = 0;
	public static final int VER_NDX_LOCAL = 0; // for .gnu.version, the symbol is local to the library
	public static final int VER_NDX_GLOBAL = 1; // for .gnu.version, the symbol carries no version of its own
	public static final int VER_NDX_HIDDEN = 0x8000; // for .gnu.version, the definition is not the default for its name

	public static boolean isElfMagic(byte[] h) {
		return h.length >= 4 && h[0] == ELFMAG0 && h[1] == ELFMAG1 && h[2] == ELFMAG2 && h[3] == ELFMAG3;
	}

	public static ElfBlob parse(BSource blob) {
		// e_ident (first 16 bytes) is format-independent, so read the full 64-bit header size up front:
		// a single read covers both the identification and the rest of the header, and the shorter
		// 32-bit header (52 bytes) fits within these 64 bytes.
		ByteBuffer hdr = blob.read(64);
		if (!(hdr.get(EI_MAG0) == ELFMAG0 && hdr.get(EI_MAG1) == ELFMAG1 && hdr.get(EI_MAG2) == ELFMAG2 && hdr.get(EI_MAG3) == ELFMAG3)) {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = hdr.get(EI_CLASS) == ELFCLASS64;
		ByteOrder order = hdr.get(EI_DATA) == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
		hdr.order(order);

		return is64 ? new Elf64Blob(hdr, blob, order) : new Elf32Blob(hdr, blob, order);
	}

	public ElfHeader header() {
		return new ElfHeader();
	}

	public ElfSectionTable sections() {
		return new ElfSectionTable(this);
	}

	public final class ElfHeader {
		public int e_type() {
			return ElfBlob.this.e_type();
		}

		public int e_machine() {
			return ElfBlob.this.e_machine();
		}

		public int e_ident(int eni) {
			return hdr.get(eni);
		}
	}

	protected int e_type() {
		return asUnsigned(hdr.getShort(16));
	}

	protected int e_machine() {
		return asUnsigned(hdr.getShort(18));
	}

	public static final class ElfSectionTable implements Iterable<ElfSectionHeader>, AutoCloseable {
		private final ElfBlob blob;
		private MappedByteBuffer sht = null;
		private final int e_shnum;
		private final int e_shentsize;

		public ElfSectionTable(ElfBlob blob) {
			this.e_shnum = blob.e_shnum();
			this.e_shentsize = blob.e_shentsize();
			this.blob = blob;
		}

		public ElfBlob owner() {
			return blob;
		}

		public long size() {
			return e_shnum;
		}

		private ByteBuffer sht() {
			if (sht == null) {
				long e_shoff = blob.e_shoff();

				// TODO: Do we need this?
				if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) {
					// No section headers: this reader resolves exports through them, so we cannot read the ABI.
					throw new RuntimeException("ELF shared library has no section headers");
				}

				sht = (MappedByteBuffer) blob.source.mmap(e_shoff, (long) e_shentsize * e_shnum).order(blob.order);
			}

			return sht;
		}

		public ElfSectionHeader get(int index) {
			ByteBuffer sht = sht();
			int sh = index * e_shentsize;

			// TODO: We should rename the sh_offset, etc, offset to "base offset of the section header entry"
			return new ElfSectionHeader() {
				@Override
				public ElfSectionTable owner() {
					return ElfSectionTable.this;
				}

				@Override
				public int type() {
					return sht.getInt(sh + 4);
				}

				@Override
				public long offset() {
					return blob.sh_offset(sht, sh);
				}

				@Override
				public long size() {
					return blob.sh_size(sht, sh);
				}

				@Override
				public int link() {
					return blob.sh_link(sht, sh);
				}

				@Override
				public long entsize() {
					return blob.sh_entsize(sht, sh);
				}
			};
		}

		@Override
		public Iterator<ElfSectionHeader> iterator() {
			final int e_shentsize = blob.e_shentsize();
			final ByteBuffer sht = sht();
			final int e_shnum = blob.e_shnum();
			return new Iterator<ElfSectionHeader>() {
				private int i = 0;

				@Override
				public boolean hasNext() {
					return i < e_shnum;
				}

				@Override
				public ElfSectionHeader next() {
					int sh = i++ * e_shentsize;
					// TODO: We should rename the sh_offset, etc, offset to "base offset of the section header entry"
					return new ElfSectionHeader() {
						@Override
						public ElfSectionTable owner() {
							return ElfSectionTable.this;
						}

						@Override
						public int type() {
							return sht.getInt(sh + 4);
						}

						@Override
						public long offset() {
							return blob.sh_offset(sht, sh);
						}

						@Override
						public long size() {
							return blob.sh_size(sht, sh);
						}

						@Override
						public int link() {
							return blob.sh_link(sht, sh);
						}

						@Override
						public long entsize() {
							return blob.sh_entsize(sht, sh);
						}
					};
				}
			};
		}

		@Override
		public void close() {
			MappedBufferUtils.unmap(sht);
		}
	}

	public static final class ElfStringTable implements AutoCloseable {
		private final MappedByteBuffer strtab;

		public ElfStringTable(ElfSectionHeader section) {
			strtab = (MappedByteBuffer) section.owner().blob.source.mmap(section.offset(), section.size()).order(section.owner().blob.order);
		}

		public String get(long offset) {
			// TODO: offset should be int or long?
			return BinaryUtils.readCString(strtab, (int) offset);
		}

		@Override
		public void close() {
			MappedBufferUtils.unmap(strtab);
		}
	}

	public static final class ElfSymbolTable implements Iterable<ElfSymbol>, AutoCloseable {
		private final MappedByteBuffer symtab;
		private final long size;
		private final long entsize;
		private final ElfBlob blob;

		public ElfSymbolTable(ElfSectionHeader section) {
			this.symtab = (MappedByteBuffer) section.owner().blob.source.mmap(section.offset(), section.size()).order(section.owner().blob.order);
			this.size = section.size() / section.entsize();
			this.entsize = section.entsize();
			this.blob = section.owner().blob;
		}

		public long size() {
			return size;
		}

		@Override
		public Iterator<ElfSymbol> iterator() {
			return new Iterator<ElfSymbol>() {
				private int i = 0;

				@Override
				public boolean hasNext() {
					return i < size;
				}

				@Override
				public ElfSymbol next() {
					int index = i++;
					int sym = (int) (index * entsize);
					return new ElfSymbol() {
						@Override
						public int index() {
							return index;
						}

						public int name() {
							return blob.st_name(symtab, sym);
						}

						public int shndx() {
							return blob.st_shndx(symtab, sym);
						}

						public int info() {
							return blob.st_info(symtab, sym);
						}

						@Override
						public long size() {
							return blob.st_size(symtab, sym);
						}

						public int binding() {
							return blob.st_info(symtab, sym) >> 4;
						}
					};
				}
			};
		}

		@Override
		public void close() {
			if (symtab != null) {
				MappedBufferUtils.unmap(symtab);
			}
		}
	}

	public interface ElfSymbol {
		/** Where this symbol sits in its table, which is how {@code .gnu.version} refers back to it. */
		int index();

		int name();
		int shndx();
		int info();
		long size();
		int binding();
	}

	/**
	 * The {@code .gnu.version} section: one half-word per {@code .dynsym} entry, giving the version index the
	 * symbol is bound to. A half-word is two bytes in either ELF class, so this needs no per-class accessor.
	 */
	public static final class ElfVersymTable implements AutoCloseable {
		private static final int ENTSIZE = 2;

		private final MappedByteBuffer versym;
		private final long count;

		public ElfVersymTable(ElfSectionHeader section) {
			ElfBlob blob = section.owner().blob;
			this.versym = (MappedByteBuffer) blob.source.mmap(section.offset(), section.size()).order(blob.order);
			this.count = section.size() / ENTSIZE;
		}

		/**
		 * The raw entry for the symbol at {@code symbolIndex}, or {@code VER_NDX_GLOBAL} past the end.
		 * {@code VER_NDX_HIDDEN} is left in place: one name can be defined at several versions at once, and
		 * the definition without that bit is the one an unversioned reference binds to, so it separates two
		 * otherwise identical entries rather than decorating one.
		 */
		public int get(int symbolIndex) {
			if (symbolIndex < 0 || symbolIndex >= count) {
				return VER_NDX_GLOBAL;
			}
			return asUnsigned(versym.getShort(symbolIndex * ENTSIZE));
		}

		@Override
		public void close() {
			MappedBufferUtils.unmap(versym);
		}
	}

	/**
	 * The {@code .gnu.version_d} section: a chain of version definitions, each holding a chain of auxiliary
	 * records whose first entry names the version. Every field is a half-word or a word, so, as with
	 * {@link ElfVersymTable}, the layout does not vary with the ELF class.
	 */
	public static final class ElfVerdefTable implements AutoCloseable {
		private static final int VD_NDX = 4; // half-word, the index .gnu.version refers to
		private static final int VD_CNT = 6; // half-word, how many auxiliary records follow
		private static final int VD_AUX = 12; // word, offset from this definition to its first auxiliary record
		private static final int VD_NEXT = 16; // word, offset from this definition to the next, zero ends the chain
		private static final int VDA_NAME = 0; // word, offset of the version name into .dynstr

		private final MappedByteBuffer verdef;
		private final long size;

		public ElfVerdefTable(ElfSectionHeader section) {
			ElfBlob blob = section.owner().blob;
			this.verdef = (MappedByteBuffer) blob.source.mmap(section.offset(), section.size()).order(blob.order);
			this.size = section.size();
		}

		/** Walks the chain, handing each definition's index and the offset of its name in {@code .dynstr}. */
		public void forEach(VerdefVisitor visitor) {
			int off = 0;
			while (off >= 0 && off + VD_NEXT < size) {
				int aux = verdef.getInt(off + VD_AUX);
				if (asUnsigned(verdef.getShort(off + VD_CNT)) > 0 && aux != 0) {
					visitor.visitVersionDefinition(asUnsigned(verdef.getShort(off + VD_NDX)), asUnsigned(verdef.getInt(off + aux + VDA_NAME)));
				}

				int next = verdef.getInt(off + VD_NEXT);
				if (next <= 0) break; // a non-advancing offset would loop forever
				off += next;
			}
		}

		public interface VerdefVisitor {
			void visitVersionDefinition(int index, long nameOffset);
		}

		@Override
		public void close() {
			MappedBufferUtils.unmap(verdef);
		}
	}

	public interface ElfSectionHeader {
		ElfSectionTable owner();

		int type();
		long offset();
		long size();
		int link();
		long entsize();
	}

	private static final class Elf64Blob extends ElfBlob {
		Elf64Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int dt_entsize() {
			return 16;
		}

		@Override
		protected long d_tag(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off));
		}

		@Override
		protected long d_val(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 8));
		}

		@Override
		protected int st_info(ByteBuffer buf, long off) {
			return asUnsigned(buf.get(requireInt(off + 4)));
		}

		@Override
		protected long st_size(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 16));
		}

		@Override
		protected int st_shndx(ByteBuffer buf, long off) {
			return asUnsigned(buf.getShort(requireInt(off + 6)));
		}

		@Override
		protected long e_shoff() {
			return hdr.getLong(40);
		}

		@Override
		protected int e_shentsize() {
			return asUnsigned(hdr.getShort(58));
		}

		@Override
		protected int e_shnum() {
			return asUnsigned(hdr.getShort(60));
		}

		@Override
		protected long sh_offset(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 24));
		}

		@Override
		protected long sh_size(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 32));
		}

		@Override
		protected int sh_link(ByteBuffer buf, long off) {
			return buf.getInt(requireInt(off + 40));
		}

		@Override
		protected long sh_entsize(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 56));
		}
	}

	private static final class Elf32Blob extends ElfBlob {
		Elf32Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int dt_entsize() {
			return 8;
		}

		@Override
		protected long d_tag(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off)));
		}

		@Override
		protected long d_val(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 4)));
		}


		@Override
		protected int st_info(ByteBuffer buf, long off) {
			return asUnsigned(buf.get(requireInt(off + 12)));
		}

		@Override
		protected long st_size(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 8)));
		}

		@Override
		protected int st_shndx(ByteBuffer buf, long off) {
			return asUnsigned(buf.getShort(requireInt(off + 14)));
		}

		@Override
		protected long e_shoff() {
			return asUnsigned(hdr.getInt(32));
		}

		@Override
		protected int e_shentsize() {
			return asUnsigned(hdr.getShort(46));
		}

		@Override
		protected int e_shnum() {
			return asUnsigned(hdr.getShort(48));
		}

		@Override
		protected long sh_offset(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 16)));
		}

		@Override
		protected long sh_size(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 20)));
		}

		@Override
		protected int sh_link(ByteBuffer buf, long off) {
			return buf.getInt(requireInt(off + 24));
		}

		@Override
		protected long sh_entsize(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 36)));
		}
	}

	protected final ByteBuffer hdr;
	/*private*/ final BSource source;
	/*private*/ final ByteOrder order;

	protected abstract long e_shoff();
	protected abstract int e_shentsize();
	protected abstract int e_shnum();

	protected abstract long sh_offset(ByteBuffer buf, long off);
	protected abstract long sh_size(ByteBuffer buf, long off);
	protected abstract int sh_link(ByteBuffer buf, long off);
	protected abstract long sh_entsize(ByteBuffer buf, long off);

	protected ElfBlob(ByteBuffer hdr, BSource source, ByteOrder order) {
		this.hdr = hdr;
		this.source = source;
		this.order = order;
	}

	protected abstract int dt_entsize();
	protected abstract long d_tag(ByteBuffer buf, long off);
	protected abstract long d_val(ByteBuffer buf, long off);

	protected int st_name(ByteBuffer buf, long off) {
		return buf.getInt(requireInt(off));
	}

	protected abstract int st_info(ByteBuffer buf, long off);
	protected abstract long st_size(ByteBuffer buf, long off);
	protected abstract int st_shndx(ByteBuffer buf, long off);
}
