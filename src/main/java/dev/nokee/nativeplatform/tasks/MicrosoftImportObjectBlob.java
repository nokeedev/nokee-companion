package dev.nokee.nativeplatform.tasks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.asUnsigned;
import static dev.nokee.nativeplatform.tasks.BinaryUtils.readCString;

/**
 * Reads a Microsoft short import object down to what an ABI is made of: the one symbol it <em>exports</em>
 * and the DLL that defines it. It is what an import library ({@code .lib}) holds in place of an object, one
 * member per exported symbol, and it is not COFF: it has no section, no symbol table and no string table —
 * only a fixed header and the two names that follow it. What it stands for is what its {@link #nameType()}
 * says, which is left to the reader.
 *
 * <p>Its signature is what tells it apart from the COFF object it sits next to in a library: a machine type
 * of {@code IMAGE_FILE_MACHINE_UNKNOWN} followed by {@code 0xFFFF}, which no COFF header can spell — hence
 * {@link #isImportObjectMagic(byte[])} being the check a walk of a library's members makes first.
 *
 * <p>Everything is read relative to the {@link BSource}, never to the enclosing channel, so a library member
 * is handled like any other blob. The format is always little-endian.
 */
// Care was taken to avoid as many condition and allocation as possible
// See https://github.com/tpn/winsdk-10/blob/master/Include/10.0.16299.0/um/winnt.h#L18344
// TODO: implements common BinaryBlob interface
final class MicrosoftImportObjectBlob {
	private static final int IMPORT_OBJECT_SIG1 = 0x0000; // IMAGE_FILE_MACHINE_UNKNOWN
	private static final int IMPORT_OBJECT_SIG2 = 0xFFFF;

	public static final int IMPORT_OBJECT_CODE = 0; // for type
	public static final int IMPORT_OBJECT_DATA = 1; // for type
	public static final int IMPORT_OBJECT_CONST = 2; // for type

	public static final int IMPORT_OBJECT_ORDINAL = 0; // for nameType, the export is named by its ordinal alone
	public static final int IMPORT_OBJECT_NAME = 1; // for nameType
	public static final int IMPORT_OBJECT_NAME_NOPREFIX = 2; // for nameType
	public static final int IMPORT_OBJECT_NAME_UNDECORATE = 3; // for nameType
	public static final int IMPORT_OBJECT_NAME_EXPORTAS = 4; // for nameType

	private static final int IMPORT_OBJECT_HEADER_SIZE = 20; // in bytes

	public static boolean isImportObjectMagic(byte[] h) {
		return h.length >= 4 && h[0] == 0x00 && h[1] == 0x00 && h[2] == (byte) 0xFF && h[3] == (byte) 0xFF;
	}

	public static MicrosoftImportObjectBlob parse(BSource source) {
		ByteBuffer hdr = ByteBuffer.allocate(IMPORT_OBJECT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		hdr.limit(Math.max(source.read(hdr), 0));
		if (hdr.limit() < IMPORT_OBJECT_HEADER_SIZE
			|| asUnsigned(hdr.getShort(0)) != IMPORT_OBJECT_SIG1
			|| asUnsigned(hdr.getShort(2)) != IMPORT_OBJECT_SIG2) {
			throw new IllegalArgumentException("not a short import object");
		}
		return new MicrosoftImportObjectBlob(hdr, source);
	}

	private final ByteBuffer hdr;
	private final BSource source;
	private ByteBuffer data = null;

	private MicrosoftImportObjectBlob(ByteBuffer hdr, BSource source) {
		this.hdr = hdr;
		this.source = source;
	}

	/** The architecture the export targets, an {@code IMAGE_FILE_MACHINE_*} value. */
	public int machine() {
		return asUnsigned(hdr.getShort(6));
	}

	public int version() {
		return asUnsigned(hdr.getShort(4));
	}

	/** Size in bytes of the names that follow the header. */
	public long sizeOfData() {
		return asUnsigned(hdr.getInt(12));
	}

	/** The ordinal the symbol is exported at, or the hint of its name, following {@link #nameType()}. */
	public int ordinalOrHint() {
		return asUnsigned(hdr.getShort(16));
	}

	/** What is exported — {@code IMPORT_OBJECT_CODE}, {@code IMPORT_OBJECT_DATA}, {@code IMPORT_OBJECT_CONST}. */
	public int type() {
		return asUnsigned(hdr.getShort(18)) & 0x3;
	}

	/** How the name relates to the exported one, {@code IMPORT_OBJECT_ORDINAL} standing for no name at all. */
	public int nameType() {
		return (asUnsigned(hdr.getShort(18)) >> 2) & 0x7;
	}

	/** The symbol the object exports, or {@literal null} when the object carries no name. */
	public String symbolName() {
		ByteBuffer data = data();
		return data.limit() == 0 ? null : readCString(data, 0);
	}

	/** The DLL the symbol comes from, which follows the symbol name, or {@literal null} when absent. */
	public String dllName() {
		ByteBuffer data = data();
		int i = 0;
		while (i < data.limit() && data.get(i) != 0) i++;
		return i + 1 < data.limit() ? readCString(data, i + 1) : null;
	}

	/** The names that follow the header, mapped once as both are read out of the same region. */
	private ByteBuffer data() {
		if (data == null) {
			long sizeOfData = sizeOfData();
			data = sizeOfData == 0 ? ByteBuffer.allocate(0) : source.mmap(IMPORT_OBJECT_HEADER_SIZE, sizeOfData);
		}

		return data;
	}
}
