package dev.nokee.nativeplatform.tasks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;
import static dev.nokee.nativeplatform.tasks.BinaryUtils.readCString;
import static dev.nokee.nativeplatform.tasks.BinaryUtils.requireInt;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Reads a COFF blob down to what an ABI is made of: the symbols an object's symbol table holds — the
 * undefined ones it <em>imports</em>, the ones it defines. Nothing else of the format is modelled; what
 * makes a symbol interesting, and whether the machine it targets is one worth reading, is left to the
 * reader.
 *
 * <p>COFF has no magic to check: an object starts with nothing but its machine type, so anything handed to
 * {@link #parse(BSource)} reads as a {@link CoffObjectBlob}. A library member that is not an object — the
 * {@link MicrosoftImportObjectBlob short import object} an import library holds — is told apart by its own
 * signature, which is a check the walk of the members makes before parsing one as COFF.
 *
 * <p>A symbol name is spelled two ways: inline in a fixed 8-byte field, or as an offset into the string
 * table that follows the symbol table. A {@link CoffSymbol} hands back the inline form through
 * {@link CoffSymbol#name() name()} and the offset through {@link CoffSymbol#strx() strx()}
 * ({@literal -1} when the name is inline), which a reader resolves through {@link CoffStringTable}.
 *
 * <p>Everything is read relative to the {@link BSource}, never to the enclosing channel, so a standalone
 * object and a library member are handled the same way. COFF is always little-endian.
 */
// Care was taken to avoid as many condition and allocation as possible
// TODO: implements BinaryBlob common interface
// TODO: Prevent CoffBlob from being extended outside of this class via correct visibility of the constructor
abstract class CoffBlob {
	private static final int IMAGE_FILE_MACHINE_UNKNOWN      = 0x0000;
	private static final int IMAGE_FILE_MACHINE_ALPHA        = 0x0184;
	private static final int IMAGE_FILE_MACHINE_ALPHA64      = 0x0284;
	private static final int IMAGE_FILE_MACHINE_AM33         = 0x01D3;
	private static final int IMAGE_FILE_MACHINE_AMD64        = 0x8664;
	private static final int IMAGE_FILE_MACHINE_ARM          = 0x01C0;
	private static final int IMAGE_FILE_MACHINE_ARM64        = 0xAA64;
	private static final int IMAGE_FILE_MACHINE_ARM64EC      = 0xA641;
	private static final int IMAGE_FILE_MACHINE_ARM64X       = 0xA64E;
	private static final int IMAGE_FILE_MACHINE_ARMNT        = 0x01C4;
	//AXP64 => 0x0284
	private static final int IMAGE_FILE_MACHINE_CEE          = 0xC0EE;//
	private static final int IMAGE_FILE_MACHINE_CEF          = 0x0CEF;//
	private static final int IMAGE_FILE_MACHINE_EBC          = 0x0EBC;
	private static final int IMAGE_FILE_MACHINE_I386         = 0x014C;
	private static final int IMAGE_FILE_MACHINE_IA64         = 0x0200;
	private static final int IMAGE_FILE_MACHINE_LOONGARCH32  = 0x6232;
	private static final int IMAGE_FILE_MACHINE_LOONGARCH64  = 0x6264;
	private static final int IMAGE_FILE_MACHINE_M32R         = 0x9041;
	private static final int IMAGE_FILE_MACHINE_MIPS16       = 0x0266;
	private static final int IMAGE_FILE_MACHINE_MIPSFPU      = 0x0366;
	private static final int IMAGE_FILE_MACHINE_MIPSFPU16    = 0x0466;
	private static final int IMAGE_FILE_MACHINE_POWERPC      = 0x01F0;
	private static final int IMAGE_FILE_MACHINE_POWERPCFP    = 0x01F1;
	//R3000BE => 0x0160
	//R3000 => 0x0162
	private static final int IMAGE_FILE_MACHINE_R4000        = 0x0166;//
	//R10000 => 0x0168
	private static final int IMAGE_FILE_MACHINE_RISCV32      = 0x5032;
	private static final int IMAGE_FILE_MACHINE_RISCV64      = 0x5064;
	private static final int IMAGE_FILE_MACHINE_RISCV128     = 0x5128;
	private static final int IMAGE_FILE_MACHINE_SH3          = 0x01A2;
	private static final int IMAGE_FILE_MACHINE_SH3DSP       = 0x01A3;
	private static final int IMAGE_FILE_MACHINE_SH3E         = 0x01A4;//
	private static final int IMAGE_FILE_MACHINE_SH4          = 0x01A6;
	private static final int IMAGE_FILE_MACHINE_SH5          = 0x01A8;
	private static final int IMAGE_FILE_MACHINE_THUMB        = 0x01C2;
	private static final int IMAGE_FILE_MACHINE_TRICORE      = 0x0520;//
	private static final int IMAGE_FILE_MACHINE_WCEMIPSV2    = 0x0169;

	private static final Set<Integer> IMAGE_FILE_MACHINES = new HashSet<>(Arrays.asList(
		IMAGE_FILE_MACHINE_UNKNOWN,
		IMAGE_FILE_MACHINE_ALPHA,
		IMAGE_FILE_MACHINE_ALPHA64,
		IMAGE_FILE_MACHINE_AM33,
		IMAGE_FILE_MACHINE_AMD64,
		IMAGE_FILE_MACHINE_ARM,
		IMAGE_FILE_MACHINE_ARM64,
		IMAGE_FILE_MACHINE_ARM64EC,
		IMAGE_FILE_MACHINE_ARM64X,
		IMAGE_FILE_MACHINE_ARMNT,
		IMAGE_FILE_MACHINE_CEE,
		IMAGE_FILE_MACHINE_CEF,
		IMAGE_FILE_MACHINE_EBC,
		IMAGE_FILE_MACHINE_I386,
		IMAGE_FILE_MACHINE_IA64,
		IMAGE_FILE_MACHINE_LOONGARCH32,
		IMAGE_FILE_MACHINE_LOONGARCH64,
		IMAGE_FILE_MACHINE_M32R,
		IMAGE_FILE_MACHINE_MIPS16,
		IMAGE_FILE_MACHINE_MIPSFPU,
		IMAGE_FILE_MACHINE_MIPSFPU16,
		IMAGE_FILE_MACHINE_POWERPC,
		IMAGE_FILE_MACHINE_POWERPCFP,
		IMAGE_FILE_MACHINE_R4000,
		IMAGE_FILE_MACHINE_RISCV32,
		IMAGE_FILE_MACHINE_RISCV64,
		IMAGE_FILE_MACHINE_RISCV128,
		IMAGE_FILE_MACHINE_SH3,
		IMAGE_FILE_MACHINE_SH3DSP,
		IMAGE_FILE_MACHINE_SH3E,
		IMAGE_FILE_MACHINE_SH4,
		IMAGE_FILE_MACHINE_SH5,
		IMAGE_FILE_MACHINE_THUMB,
		IMAGE_FILE_MACHINE_TRICORE,
		IMAGE_FILE_MACHINE_WCEMIPSV2
	));

	public static final int IMAGE_SYM_UNDEFINED = 0; // for sectionNumber
	public static final int IMAGE_SYM_CLASS_EXTERNAL = 2; // for storageClass
	public static final int IMAGE_SYM_CLASS_WEAK_EXTERNAL = 105; // for storageClass

	private static final int COFF_HEADER_SIZE = 20; // in bytes
	private static final int SYMBOL_SIZE = 18; // in bytes
	private static final int SYMBOL_NAME_LENGTH = 8; // in bytes
	private static final int STRING_TABLE_SIZE_LENGTH = 4; // in bytes, the size field is counted by the size itself

	public static boolean isCoffMagic(byte[] h) {
		int machine = (h[0] & 0xFF) | ((h[1] & 0xFF) << Byte.SIZE);
		return IMAGE_FILE_MACHINES.contains(machine);
	}

	/**
	 * Reads the file header and returns the object it heads. Whether the machine it targets is one a reader
	 * knows what to do with is the reader's to decide.
	 */
	public static CoffBlob parse(BSource source) {
		ByteBuffer hdr = read(source, 0, COFF_HEADER_SIZE);
		if (hdr.limit() < COFF_HEADER_SIZE) {
			throw new IllegalArgumentException("not a COFF file");
		}

		return new CoffObjectBlob(hdr, source);
	}

	/** The architecture the blob targets, an {@code IMAGE_FILE_MACHINE_*} value. */
	public abstract int machine();

	/**
	 * A relocatable object, read through the symbol table and the string table its header points at. The
	 * symbols it imports are the external ones it leaves undefined ({@code IMAGE_SYM_UNDEFINED}) with a
	 * value of zero — a non-zero value being a common symbol, which is a definition of its own.
	 */
	public static final class CoffObjectBlob extends CoffBlob {
		CoffObjectBlob(ByteBuffer hdr, BSource source) {
			super(hdr, source);
		}

		@Override
		public int machine() {
			return asUnsigned(hdr.getShort(0));
		}

		public CoffSymbolTable symbols() {
			return new CoffSymbolTable(this);
		}

		public CoffStringTable strings() {
			return new CoffStringTable(this);
		}

		/** Offset of the symbol table, relative to the start of the source; zero when the object has none. */
		long pointerToSymbolTable() {
			return asUnsigned(hdr.getInt(8));
		}

		/** Number of entries of the symbol table, auxiliary records included. */
		int numberOfSymbols() {
			return hdr.getInt(12);
		}
	}

	/**
	 * The symbol table of an object. An auxiliary record follows the symbol it belongs to and occupies an
	 * entry of the table without being a symbol of its own, so {@link #iterator()} steps over them; they stay
	 * reachable through {@link #get(int)}, which is also how a symbol referenced by index is read.
	 */
	public static final class CoffSymbolTable implements Iterable<CoffSymbol>, AutoCloseable {
		private final CoffObjectBlob blob;
		private final int numberOfSymbols;
		private MappedByteBuffer symtab = null;

		public CoffSymbolTable(CoffObjectBlob blob) {
			this.blob = blob;
			this.numberOfSymbols = blob.numberOfSymbols();
		}

		public CoffObjectBlob owner() {
			return blob;
		}

		/** Number of entries of the table, auxiliary records included. */
		public long size() {
			return numberOfSymbols;
		}

		private ByteBuffer symtab() {
			if (symtab == null) {
				// Every symbol is read out of this region, so it is mapped once rather than entry by entry.
				long pointerToSymbolTable = blob.pointerToSymbolTable();
				assert !(pointerToSymbolTable == 0 || numberOfSymbols == 0);
				symtab = (MappedByteBuffer) blob.source.mmap(pointerToSymbolTable, (long) numberOfSymbols * SYMBOL_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			}

			return symtab;
		}

		/** The entry at {@code index}, which may be an auxiliary record rather than a symbol. */
		public CoffSymbol get(int index) {
			return symbol(symtab(), index);
		}

		@Override
		public Iterator<CoffSymbol> iterator() {
			final ByteBuffer symtab = symtab();
			return new Iterator<CoffSymbol>() {
				private int i = 0;

				@Override
				public boolean hasNext() {
					return i < numberOfSymbols;
				}

				@Override
				public CoffSymbol next() {
					CoffSymbol symbol = symbol(symtab, i);
					// The auxiliary records of a symbol are entries of the table, never symbols themselves.
					i += 1 + symbol.numberOfAuxSymbols();
					return symbol;
				}
			};
		}

		private CoffSymbol symbol(ByteBuffer symtab, int index) {
			final int sym = index * SYMBOL_SIZE;
			return new CoffSymbol() {
				@Override
				public CoffSymbolTable owner() {
					return CoffSymbolTable.this;
				}

				@Override
				public int index() {
					return index;
				}

				@Override
				public String name() {
					return readName(symtab, sym, SYMBOL_NAME_LENGTH);
				}

				@Override
				public long strx() {
					// A name too long to sit inline leaves the first 4 bytes zero and spells the offset in
					// the next 4; a name of exactly 8 bytes stays inline, hence no terminator to look for.
					return symtab.getInt(sym) == 0 ? asUnsigned(symtab.getInt(sym + 4)) : -1;
				}

				@Override
				public long value() {
					return asUnsigned(symtab.getInt(sym + 8));
				}

				@Override
				public int sectionNumber() {
					return symtab.getShort(sym + 12); // signed: an absolute and a debug symbol are negative
				}

				@Override
				public int storageClass() {
					return asUnsigned(symtab.get(sym + 16));
				}

				@Override
				public int numberOfAuxSymbols() {
					return asUnsigned(symtab.get(sym + 17));
				}
			};
		}

		@Override
		public void close() {
			if (symtab != null) MappedBufferUtils.unmap(symtab);
		}
	}

	/**
	 * One symbol table entry. Its name is either the inline {@link #name()} or, when {@link #strx()} is not
	 * {@literal -1}, the string the {@link CoffStringTable} holds at that offset.
	 */
	public interface CoffSymbol {
		CoffSymbolTable owner();

		/** Index of the entry in the symbol table, which is how an auxiliary record names another symbol. */
		int index();

		/** The inline name, which is the whole name unless it did not fit — see {@link #strx()}. */
		String name();

		/** Offset of the name in the string table, or {@literal -1} when the name is stored inline. */
		long strx();

		long value();

		/** The 1-based section the symbol belongs to, or {@code IMAGE_SYM_UNDEFINED} when it is referenced only. */
		int sectionNumber();

		int storageClass();
		int numberOfAuxSymbols();
	}

	/**
	 * The string table, which follows the symbol table and holds every name too long to sit inline. Offsets
	 * are counted from the start of the table, whose first 4 bytes are its own size — so the first string
	 * lives at offset 4, and a table of 4 bytes (or none at all) holds nothing.
	 */
	public static final class CoffStringTable implements AutoCloseable {
		private final MappedByteBuffer strtab;

		public CoffStringTable(CoffObjectBlob blob) {
			long pointerToSymbolTable = blob.pointerToSymbolTable();
			long base = pointerToSymbolTable + (long) blob.numberOfSymbols() * SYMBOL_SIZE;
			long size = pointerToSymbolTable == 0 ? 0 : sizeAt(blob.source, base);
			assert !(size <= STRING_TABLE_SIZE_LENGTH);
			this.strtab = blob.source.mmap(base, size);
		}

		private static long sizeAt(BSource source, long base) {
			ByteBuffer size = read(source, base, STRING_TABLE_SIZE_LENGTH);
			return size.limit() < STRING_TABLE_SIZE_LENGTH ? 0 : asUnsigned(size.getInt(0));
		}

		public String get(long offset) {
			return readCString(strtab, requireInt(offset));
		}

		@Override
		public void close() {
			if (strtab != null) MappedBufferUtils.unmap(strtab);
		}
	}

	protected final ByteBuffer hdr;
	/*private*/ final BSource source;

	protected CoffBlob(ByteBuffer hdr, BSource source) {
		this.hdr = hdr.order(ByteOrder.LITTLE_ENDIAN);
		this.source = source;
	}

	private static ByteBuffer read(BSource source, long position, int length) {
		ByteBuffer result = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		int size = source.read(result, position);
		System.out.println("READING " + size + " - position " + length);
		source.read(result, position);
		return result.limit(Math.max(size, 0));
	}

	/** Reads the fixed-lenggsth, NUL-padded name at {@code off}; a name filling the field has no terminator. */
	private static String readName(ByteBuffer buf, int off, int length) {
		byte[] b = new byte[length];
		int i = 0;
		while (i < length) {
			byte c = buf.get(off + i);
			if (c == 0) break;
			b[i++] = c;
		}
		return new String(b, 0, i, US_ASCII);
	}
}
