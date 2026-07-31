package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Reads the imports of one object image by dispatching on its magic to the ELF, Mach-O or COFF import
 * reader. Used as the per-member reader of an {@link ArchiveBinaryHasher} and directly for standalone
 * object files, so the archive walk and the object-file path make no assumption about the object format.
 * An image whose format is not recognized throws {@link IllegalArgumentException}.
 */
final class ObjectFileImportReader implements AbiObjectHasher {
	private static final byte ELFMAG0 = 0x7f;
	private static final byte ELFMAG1 = 'E';
	private static final byte ELFMAG2 = 'L';
	private static final byte ELFMAG3 = 'F';

	private final ElfBinaryHasher elfReader;
	private final MachOImportReader machOReader;
	private final CoffImportReader coffReader;

	ObjectFileImportReader() {
		this.elfReader = new ElfBinaryHasher();
		this.machOReader = new MachOImportReader();
		this.coffReader = new CoffImportReader();
	}

	@Override
	public AbiBinaryHasher.AbiBinaryHashCode hash(FileChannel channel, long base, long size) throws IOException {
		if (size < 4) {
			throw new IllegalArgumentException("object image too small to identify");
		}
		byte[] magic = BinaryUtils.readBytes(channel, base, 4);
		if (isElfMagic(magic)) {
			return elfReader.hash(channel, base);
		}
		if (isMachOMagic(asInt(magic, 0))) {
			return machOReader.hash(channel, base, size);
		}
		// COFF has no strong magic; the COFF reader validates the machine type and rejects anything else.
		return coffReader.hash(channel, base, size);
	}

	private static boolean isElfMagic(byte[] h) {
		return h[0] == ELFMAG0 && h[1] == ELFMAG1 && h[2] == ELFMAG2 && h[3] == ELFMAG3;
	}

	private static boolean isMachOMagic(int m) {
		return m == 0xFEEDFACE || m == 0xCEFAEDFE
			|| m == 0xFEEDFACF || m == 0xCFFAEDFE
			|| m == 0xCAFEBABE || m == Integer.reverseBytes(0xCAFEBABE);
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}
}
