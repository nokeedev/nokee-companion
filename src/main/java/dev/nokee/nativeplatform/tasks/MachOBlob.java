package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;
import static dev.nokee.nativeplatform.tasks.BinaryUtils.requireInt;

/**
 * Reads a Mach-O blob down to what an ABI is made of: the symbols an image's {@code LC_SYMTAB} holds — the
 * undefined ones an object file <em>references</em>, the external ones a dylib <em>defines</em> — and the
 * install name its {@code LC_ID_DYLIB} carries. Nothing else of the format is modelled; which side of the
 * symbol table to read, and what makes a symbol interesting, is left to the reader.
 *
 * <p>{@link #parse(BSource)} dispatches on the magic and returns one of the two shapes a Mach-O blob takes:
 *
 * <ul>
 *   <li>a {@link MachOUniversalBlob}, which has no image of its own — only a table of the
 *       {@link MachOUniversalBlob#architectures() architectures} it holds, each an image parsed back through
 *       this same method, so an image nested in a fat binary reads exactly as the standalone file of that
 *       architecture would;</li>
 *   <li>a {@link MachOImageBlob}, one architecture-specific image. Its 32- and 64-bit forms are read the same
 *       way; only the header size and the shape of an {@code nlist} entry differ.</li>
 * </ul>
 *
 * <p>Everything is read relative to the {@link BSource}, never to the enclosing channel, so a standalone file,
 * an archive member and a slice of a fat binary are all handled the same way.
 */
// Care was taken to avoid as many condition and allocation as possible
abstract class MachOBlob {
	private static final int MH_MAGIC = 0xFEEDFACE;
	private static final int MH_CIGAM = 0xCEFAEDFE;
	private static final int MH_MAGIC_64 = 0xFEEDFACF;
	private static final int MH_CIGAM_64 = 0xCFFAEDFE;
	private static final int FAT_MAGIC = 0xCAFEBABE;
	private static final int FAT_CIGAM = 0xBEBAFECA;
	private static final int FAT_MAGIC_64 = 0xCAFEBABF;
	private static final int FAT_CIGAM_64 = 0xBFBAFECA;

	public static final int MH_OBJECT = 1;
	public static final int MH_DYLIB = 6;
	public static final int MH_DYLIB_STUB = 9;

	private static final int LC_SYMTAB = 0x2;
	private static final int LC_DYSYMTAB = 0xB;
	private static final int LC_ID_DYLIB = 0xD;

	private static final int FAT_HEADER_SIZE = 8; // in bytes
	private static final int FAT_ARCH_SIZE = 20; // in bytes
	private static final int FAT_ARCH_64_SIZE = 32; // in bytes
	private static final int MACH_HEADER_SIZE = 28; // in bytes
	private static final int MACH_HEADER_64_SIZE = 32; // in bytes
	private static final int LOAD_COMMAND_SIZE = 8; // in bytes, the cmd/cmdsize pair every load command starts with
	private static final int NLIST_SIZE = 12; // in bytes
	private static final int NLIST_64_SIZE = 16; // in bytes

	public static boolean isMachOMagic(byte[] h) {
		return isMachOMagic(asInt(h, 0));
	}

	/**
	 * Reads the magic at the start of the source and returns the blob it calls for: a universal container, or the
	 * single image the source holds. The magic also fixes the byte order every later read uses — a byte-swapped
	 * magic ({@code MH_CIGAM}, {@code FAT_CIGAM}) is what says the fields that follow are little-endian.
	 */
	public static MachOBlob parse(BSource source) {
		// The largest header we may need is a 64-bit mach_header (32 bytes); a fat header (8 bytes) and a 32-bit
		// mach_header (28 bytes) both fit within it, so a single read covers every form the magic may select.
		ByteBuffer hdr = source.read(MACH_HEADER_64_SIZE);
		if (hdr.limit() < FAT_HEADER_SIZE) {
			throw new IllegalArgumentException("not a Mach-O file");
		}

		// The magic constants are spelled as the bytes appear on disk, hence the big-endian read: the buffer's
		// order is only fixed once the magic says which order the blob is written in.
		switch (hdr.getInt(0)) {
			case FAT_MAGIC:    return new Fat32Blob(hdr, source, ByteOrder.BIG_ENDIAN);
			case FAT_CIGAM:    return new Fat32Blob(hdr, source, ByteOrder.LITTLE_ENDIAN);
			case FAT_MAGIC_64: return new Fat64Blob(hdr, source, ByteOrder.BIG_ENDIAN);
			case FAT_CIGAM_64: return new Fat64Blob(hdr, source, ByteOrder.LITTLE_ENDIAN);
			case MH_MAGIC:     return new MachO32Blob(hdr, source, ByteOrder.BIG_ENDIAN);
			case MH_CIGAM:     return new MachO32Blob(hdr, source, ByteOrder.LITTLE_ENDIAN);
			case MH_MAGIC_64:  return new MachO64Blob(hdr, source, ByteOrder.BIG_ENDIAN);
			case MH_CIGAM_64:  return new MachO64Blob(hdr, source, ByteOrder.LITTLE_ENDIAN);
			default: throw new IllegalArgumentException("not a Mach-O file");
		}
	}

	/**
	 * A universal ("fat") binary: a container that has no header, load command or symbol of its own, only a table
	 * of the images it holds. Reading an ABI out of one means picking one of its
	 * {@link #architectures() architectures} and reading that image.
	 */
	public static abstract class MachOUniversalBlob extends MachOBlob {
		MachOUniversalBlob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr.order(order), source, order);
		}

		public MachOArchitectureTable architectures() {
			return new MachOArchitectureTable(this);
		}

		/** Number of architectures the container declares ({@code nfat_arch}). */
		public int nfat_arch() {
			return hdr.getInt(4);
		}

		/** Size of one entry of the architecture table. */
		protected abstract int fa_entsize();

		protected abstract long fa_offset(ByteBuffer buf, long off);
		protected abstract long fa_size(ByteBuffer buf, long off);
	}

	/**
	 * The images a universal binary holds, one per architecture. An entry is nothing but the range of the source
	 * its image occupies, so it is handed back as the image itself: the range becomes a slice, and the slice is
	 * parsed through {@link MachOBlob#parse(BSource)} exactly as the standalone file of that architecture would be.
	 */
	public static final class MachOArchitectureTable implements Iterable<MachOImageBlob> {
		private final MachOUniversalBlob blob;
		private final int nfat_arch;
		private final int fa_entsize;
		private ByteBuffer fat = null;

		public MachOArchitectureTable(MachOUniversalBlob blob) {
			this.blob = blob;
			this.nfat_arch = blob.nfat_arch();
			this.fa_entsize = blob.fa_entsize();
		}

		public MachOUniversalBlob owner() {
			return blob;
		}

		public long size() {
			return nfat_arch;
		}

		private ByteBuffer fat() {
			if (fat == null) {
				// The table sits right after the fat header and is walked entry by entry, so a single mapping
				// turns every per-entry read into a memory access.
				fat = blob.source.mmap(FAT_HEADER_SIZE, (long) fa_entsize * nfat_arch).order(blob.order);
			}

			return fat;
		}

		public MachOImageBlob get(int index) {
			return image(fat(), (long) index * fa_entsize);
		}

		@Override
		public Iterator<MachOImageBlob> iterator() {
			final ByteBuffer fat = fat();
			return new Iterator<MachOImageBlob>() {
				private int i = 0;

				@Override
				public boolean hasNext() {
					return i < nfat_arch;
				}

				@Override
				public MachOImageBlob next() {
					return image(fat, (long) i++ * fa_entsize);
				}
			};
		}

		/** Parses the image the entry at {@code arch} points at; a universal binary never nests another one. */
		private MachOImageBlob image(ByteBuffer fat, long arch) {
			return (MachOImageBlob) parse(blob.source.slice(blob.fa_offset(fat, arch), blob.fa_size(fat, arch)));
		}
	}

	/**
	 * One architecture-specific Mach-O image, read through its header and the load commands that follow it.
	 * Only the header size and the shape of an {@code nlist} entry separate the 32- and 64-bit forms.
	 */
	public static abstract class MachOImageBlob extends MachOBlob {
		MachOImageBlob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr.order(order), source, order);
		}

		public MachOHeader header() {
			return new MachOHeader();
		}

		public MachOLoadCommandTable loadCommands() {
			return new MachOLoadCommandTable(this);
		}

		public final class MachOHeader {
			/** What the image is — {@code MH_OBJECT}, {@code MH_DYLIB}, {@code MH_DYLIB_STUB}, and so on. */
			public int filetype() {
				return MachOImageBlob.this.filetype();
			}
		}

		protected int filetype() {
			return hdr.getInt(12);
		}

		/** Number of load commands that follow the header. */
		protected int ncmds() {
			return hdr.getInt(16);
		}

		/** Size in bytes of all the load commands taken together. */
		protected long sizeofcmds() {
			return asUnsigned(hdr.getInt(20));
		}

		/** Size of the header, which is also the offset of the first load command. */
		protected abstract int headerSize();

		/** Size of one {@code nlist} entry of the symbol table. */
		protected abstract int nlistSize();

		protected long n_strx(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off)));
		}

		protected int n_type(ByteBuffer buf, long off) {
			return asUnsigned(buf.get(requireInt(off + 4)));
		}

		protected int n_desc(ByteBuffer buf, long off) {
			return asUnsigned(buf.getShort(requireInt(off + 6)));
		}

		protected abstract long n_value(ByteBuffer buf, long off);
	}

	/**
	 * The load commands of an image, in the order they appear. A load command carries its own size rather than
	 * belonging to a fixed-size table, so the commands are reachable only by walking them from the first.
	 */
	public static final class MachOLoadCommandTable implements Iterable<MachOLoadCommand> {
		private final MachOImageBlob blob;
		private final int ncmds;
		private final long sizeofcmds;
		private ByteBuffer lc = null;

		public MachOLoadCommandTable(MachOImageBlob blob) {
			this.blob = blob;
			this.ncmds = blob.ncmds();
			this.sizeofcmds = blob.sizeofcmds();
		}

		public MachOImageBlob owner() {
			return blob;
		}

		public long size() {
			return ncmds;
		}

		private ByteBuffer lc() {
			if (lc == null) {
				// Every command is read out of this region — the walk itself, then whatever fields a reader asks
				// for — so it is mapped once rather than read command by command.
				lc = blob.source.mmap(blob.headerSize(), sizeofcmds).order(blob.order);
			}

			return lc;
		}

		@Override
		public Iterator<MachOLoadCommand> iterator() {
			final ByteBuffer lc = lc();
			return new Iterator<MachOLoadCommand>() {
				private int i = 0;
				private long off = 0;

				@Override
				public boolean hasNext() {
					if (i >= ncmds || off + LOAD_COMMAND_SIZE > sizeofcmds) return false;

					// A cmdsize that would not advance, or would walk past the commands, ends the walk: what
					// follows cannot be read as a load command.
					long cmdsize = asUnsigned(lc.getInt(requireInt(off + 4)));
					return cmdsize >= LOAD_COMMAND_SIZE && off + cmdsize <= sizeofcmds;
				}

				@Override
				public MachOLoadCommand next() {
					MachOLoadCommand command = command(lc, off);
					i++;
					off += command.cmdsize();
					return command;
				}
			};
		}

		/** Returns the command at {@code off} as whatever its {@code cmd} makes it, or a plain command otherwise. */
		private MachOLoadCommand command(ByteBuffer lc, long off) {
			switch (lc.getInt(requireInt(off))) {
				case LC_SYMTAB:   return new MachOSymtabCommand(this, lc, off);
				case LC_DYSYMTAB: return new MachODysymtabCommand(this, lc, off);
				case LC_ID_DYLIB: return new MachODylibCommand(this, lc, off);
				default:          return new MachOLoadCommand(this, lc, off);
			}
		}
	}

	public static class MachOLoadCommand {
		private final MachOLoadCommandTable owner;
		/** The mapped load command region, shared by every command of the image. */
		private final ByteBuffer lc;
		/** Base offset of this load command within that region. */
		private final long off;

		MachOLoadCommand(MachOLoadCommandTable owner, ByteBuffer lc, long off) {
			this.owner = owner;
			this.lc = lc;
			this.off = off;
		}

		public MachOLoadCommandTable owner() {
			return owner;
		}

		public int cmd() {
			return lc.getInt(requireInt(off));
		}

		public long cmdsize() {
			return asUnsigned(lc.getInt(requireInt(off + 4)));
		}

		protected int int32(int field) {
			return lc.getInt(requireInt(off + field));
		}

		protected long uint32(int field) {
			return asUnsigned(lc.getInt(requireInt(off + field)));
		}

		/**
		 * Reads the {@code lc_str} at {@code field}: an offset from the start of the command to a string that runs
		 * to the end of the command. Returns {@literal null} when the offset points outside the command.
		 */
		protected String string(int field) {
			long strOffset = uint32(field);
			long cmdsize = cmdsize();
			if (strOffset >= cmdsize) return null;
			return BinaryUtils.readCString(lc, requireInt(off + strOffset), requireInt(off + cmdsize));
		}
	}

	/**
	 * {@code LC_SYMTAB}: where the image's symbol table and its string table live. Both are offsets from the start
	 * of the image, which is what the blob's source is anchored on.
	 */
	public static final class MachOSymtabCommand extends MachOLoadCommand {
		MachOSymtabCommand(MachOLoadCommandTable owner, ByteBuffer lc, long off) {
			super(owner, lc, off);
		}

		private long symoff() {
			return uint32(8);
		}

		private int nsyms() {
			return int32(12);
		}

		private long stroff() {
			return uint32(16);
		}

		private long strsize() {
			return uint32(20);
		}

		public MachOSymbolTable symbols() {
			return new MachOSymbolTable(this);
		}

		public MachOStringTable strings() {
			return new MachOStringTable(this);
		}
	}

	/**
	 * {@code LC_DYSYMTAB}: the runs the symbol table is grouped into. The symbols an image defines externally —
	 * a dylib's exports — are the {@code nextdefsym} entries starting at {@code iextdefsym}.
	 */
	public static final class MachODysymtabCommand extends MachOLoadCommand {
		MachODysymtabCommand(MachOLoadCommandTable owner, ByteBuffer lc, long off) {
			super(owner, lc, off);
		}

		public int iextdefsym() {
			return int32(16);
		}

		public int nextdefsym() {
			return int32(20);
		}
	}

	/** {@code LC_ID_DYLIB}: the install name a dylib records for itself. */
	public static final class MachODylibCommand extends MachOLoadCommand {
		MachODylibCommand(MachOLoadCommandTable owner, ByteBuffer lc, long off) {
			super(owner, lc, off);
		}

		/** The install name, or {@literal null} when the command does not hold one. */
		public String name() {
			return string(8);
		}
	}

	public static final class MachOSymbolTable implements Iterable<MachOSymbol> {
		private final MachOImageBlob blob;
		private final ByteBuffer symtab;
		private final int nsyms;
		private final int nlistSize;

		public MachOSymbolTable(MachOSymtabCommand command) {
			this.blob = command.owner().owner();
			this.nlistSize = blob.nlistSize();
			this.nsyms = command.nsyms();
			this.symtab = blob.source.mmap(command.symoff(), (long) nsyms * nlistSize).order(blob.order);
		}

		public long size() {
			return nsyms;
		}

		/** The entry at {@code index}, which is how a {@code LC_DYSYMTAB} run is read without walking the rest. */
		public MachOSymbol get(int index) {
			return symbol((long) index * nlistSize);
		}

		@Override
		public Iterator<MachOSymbol> iterator() {
			return new Iterator<MachOSymbol>() {
				private int i = 0;

				@Override
				public boolean hasNext() {
					return i < nsyms;
				}

				@Override
				public MachOSymbol next() {
					return symbol((long) i++ * nlistSize);
				}
			};
		}

		private MachOSymbol symbol(long sym) {
			return new MachOSymbol() {
				@Override
				public long strx() {
					return blob.n_strx(symtab, sym);
				}

				@Override
				public int type() {
					return blob.n_type(symtab, sym);
				}

				@Override
				public int desc() {
					return blob.n_desc(symtab, sym);
				}

				@Override
				public long value() {
					return blob.n_value(symtab, sym);
				}
			};
		}

		public Iterable<MachOSymbol> range(int fromIndex, int toIndex) {
			// TODO: Check within bounds
			return new Iterable<MachOSymbol>() {
				@Override
				public Iterator<MachOSymbol> iterator() {
					return new Iterator<MachOSymbol>() {
						private int i = fromIndex;

						@Override
						public boolean hasNext() {
							return i < toIndex;
						}

						@Override
						public MachOSymbol next() {
							return symbol((long) i++ * nlistSize);
						}
					};
				}
			};
		}
	}

	/** One {@code nlist} entry; its name is the {@link #strx() index} into the {@link MachOStringTable}. */
	public interface MachOSymbol {
		long strx();
		int type();
		int desc();
		long value();
	}

	public static final class MachOStringTable {
		private final ByteBuffer strtab;

		public MachOStringTable(MachOSymtabCommand command) {
			MachOImageBlob blob = command.owner().owner();
			this.strtab = blob.source.mmap(command.stroff(), command.strsize());
		}

		public String get(long offset) {
			return BinaryUtils.readCString(strtab, requireInt(offset));
		}
	}

	/** A universal binary whose table is made of {@code fat_arch_64} entries, with 64-bit offset and size. */
	private static final class Fat64Blob extends MachOUniversalBlob {
		Fat64Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int fa_entsize() {
			return FAT_ARCH_64_SIZE;
		}

		@Override
		protected long fa_offset(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 8));
		}

		@Override
		protected long fa_size(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 16));
		}
	}

	/** A universal binary whose table is made of {@code fat_arch} entries, with 32-bit offset and size. */
	private static final class Fat32Blob extends MachOUniversalBlob {
		Fat32Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int fa_entsize() {
			return FAT_ARCH_SIZE;
		}

		@Override
		protected long fa_offset(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 8)));
		}

		@Override
		protected long fa_size(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 12)));
		}
	}

	private static final class MachO64Blob extends MachOImageBlob {
		MachO64Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int headerSize() {
			return MACH_HEADER_64_SIZE;
		}

		@Override
		protected int nlistSize() {
			return NLIST_64_SIZE;
		}

		@Override
		protected long n_value(ByteBuffer buf, long off) {
			return buf.getLong(requireInt(off + 8));
		}
	}

	private static final class MachO32Blob extends MachOImageBlob {
		MachO32Blob(ByteBuffer hdr, BSource source, ByteOrder order) {
			super(hdr, source, order);
		}

		@Override
		protected int headerSize() {
			return MACH_HEADER_SIZE;
		}

		@Override
		protected int nlistSize() {
			return NLIST_SIZE;
		}

		@Override
		protected long n_value(ByteBuffer buf, long off) {
			return asUnsigned(buf.getInt(requireInt(off + 8)));
		}
	}

	protected final ByteBuffer hdr;
	/*private*/ final BSource source;
	/*private*/ final ByteOrder order;

	protected MachOBlob(ByteBuffer hdr, BSource source, ByteOrder order) {
		this.hdr = hdr;
		this.source = source;
		this.order = order;
	}

	private static boolean isMachOMagic(int m) {
		return m == MH_MAGIC || m == MH_CIGAM || m == MH_MAGIC_64 || m == MH_CIGAM_64
			|| m == FAT_MAGIC || m == FAT_CIGAM || m == FAT_MAGIC_64 || m == FAT_CIGAM_64;
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}
}
