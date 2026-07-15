package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class DefaultNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n

	private ElfBinaryHasher elfHasher;
	private MachOBinaryHasher machOHasher;
	private ImportLibraryBinaryHasher importLibraryHasher;

	private final ByteBuffer buffer = ByteBuffer.allocate(8);

	public Object hash(Path library) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			if (channel.size() < 8) {
				return library;
			}
			byte[] header = BinaryUtils.readInto(channel, 0, buffer,8).array();

			AbiBinaryHasher hasher;
			if (isElfMagic(header)) {
				hasher = elfHasher();
			} else if (isMachOMagic(header)) {
				hasher = machOHasher();
			} else if (isArMagic(header)) {
				hasher = importLibraryHasher();
			} else {
				return library;
			}

			return hasher.hash(channel);
		} catch (NotASharedLibraryException e) {
			return library; // should not get here
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private AbiBinaryHasher elfHasher() {
		if (elfHasher == null) {
			elfHasher = new ElfBinaryHasher();
		}
		return elfHasher;
	}

	private AbiBinaryHasher machOHasher() {
		if (machOHasher == null) {
			machOHasher = new MachOBinaryHasher();
		}
		return machOHasher;
	}

	private AbiBinaryHasher importLibraryHasher() {
		if (importLibraryHasher == null) {
			importLibraryHasher = new ImportLibraryBinaryHasher();
		}
		return importLibraryHasher;
	}

	private static boolean isElfMagic(byte[] h) {
		return h[0] == ELF_MAGIC[0] && h[1] == ELF_MAGIC[1]
			&& h[2] == ELF_MAGIC[2] && h[3] == ELF_MAGIC[3];
	}

	private static boolean isMachOMagic(byte[] h) {
		int m = asInt(h, 0);
		return m == 0xFEEDFACE || m == 0xCEFAEDFE
			|| m == 0xFEEDFACF || m == 0xCFFAEDFE
			|| m == 0xCAFEBABE || m == Integer.reverseBytes(0xCAFEBABE);
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) return false;
		}
		return true;
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}
}
