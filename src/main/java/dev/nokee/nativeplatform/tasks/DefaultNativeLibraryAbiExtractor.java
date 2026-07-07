package dev.nokee.nativeplatform.tasks;

import org.gradle.api.model.ObjectFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

final class DefaultNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n

	private final ElfAbiExtractor elfExtractor;
	private final MachOAbiExtractor machOExtractor;
	private final ImportLibraryAbiExtractor importLibraryExtractor;

	public DefaultNativeLibraryAbiExtractor(ObjectFactory objects) {
		elfExtractor = new ElfAbiExtractor(objects);
		machOExtractor = new MachOAbiExtractor(objects);
		importLibraryExtractor = new ImportLibraryAbiExtractor(objects);
	}

	public @Nullable AbiModel extract(Path library) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			if (channel.size() < 8) {
				return null;
			}
			byte[] header = BinaryUtils.readBytes(channel, 0, 8);

			if (isElfMagic(header)) {
				return elfExtractor.extract(channel);
			} else if (isMachOMagic(header)) {
				return machOExtractor.extract(channel, header);
			} else if (isArMagic(header)) {
				try {
					if (isWindowsImportLibrary(channel)) {
						return importLibraryExtractor.extract(library);
					}
				} catch (IOException ignored) {}
				return null;
			} else {
				return null;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean isWindowsImportLibrary(FileChannel channel) throws IOException {
		long offset = 8; // skip !<arch>\n
		while (offset + 60 <= channel.size()) {
			byte[] hdrBytes = BinaryUtils.readBytes(channel, offset, 60);
			String name = parseArMemberName(hdrBytes);
			long size = parseArMemberSize(hdrBytes);
			if (size < 0) break;

			long dataOffset = offset + 60;
			offset = dataOffset + size;
			if (size % 2 != 0) offset++;

			if (name.startsWith("/")) continue; // skip linker and long name members

			if (dataOffset + 4 > channel.size()) break;
			byte[] sig = BinaryUtils.readBytes(channel, dataOffset, 4);
			// IMPORT_OBJECT_HEADER: Sig1=0x0000, Sig2=0xFFFF
			return sig[0] == 0x00 && sig[1] == 0x00 && (sig[2] & 0xFF) == 0xFF && (sig[3] & 0xFF) == 0xFF;
		}
		return false;
	}

	static String parseArMemberName(byte[] hdr) {
		int end = 16;
		while (end > 0 && hdr[end - 1] == ' ') end--;
		if (end > 0 && hdr[end - 1] == '/') end--;
		return new String(hdr, 0, end, StandardCharsets.US_ASCII);
	}

	static long parseArMemberSize(byte[] hdr) {
		String sizeStr = new String(hdr, 48, 10, StandardCharsets.US_ASCII).trim();
		try {
			return Long.parseLong(sizeStr);
		} catch (NumberFormatException e) {
			return -1;
		}
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

	private static final class ImportLibraryAbiExtractor {
		// NameType=0 means IMPORT_ORDINAL (no symbol name in data); represented as "#<ordinal>"
		private static final int IMPORT_ORDINAL = 0;

		private final ObjectFactory objects;

		ImportLibraryAbiExtractor(ObjectFactory objects) {
			this.objects = objects;
		}

		public AbiModel extract(Path library) throws IOException {
			try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
				return parse(channel);
			}
		}

		private AbiModel parse(FileChannel channel) throws IOException {
			long offset = 8; // skip !<arch>\n
			String dllName = null;
			List<ExportedSymbol> symbols = new ArrayList<>();

			while (offset + 60 <= channel.size()) {
				byte[] hdrBytes = BinaryUtils.readBytes(channel, offset, 60);
				String memberName = parseArMemberName(hdrBytes);
				long memberSize = parseArMemberSize(hdrBytes);
				if (memberSize < 0) break;

				long dataOffset = offset + 60;
				offset = dataOffset + memberSize;
				if (memberSize % 2 != 0) offset++;

				if (memberName.startsWith("/")) continue; // skip linker/long-name members
				if (memberSize < 20) continue;

				byte[] sig = BinaryUtils.readBytes(channel, dataOffset, 4);
				if (sig[0] != 0x00 || sig[1] != 0x00 || (sig[2] & 0xFF) != 0xFF || (sig[3] & 0xFF) != 0xFF) {
					continue; // not a short import record
				}

				ByteBuffer importHdr = BinaryUtils.readAt(channel, dataOffset, 20);
				importHdr.order(ByteOrder.LITTLE_ENDIAN);
				int sizeOfData = importHdr.getInt(12);
				int ordinalOrHint = importHdr.getShort(16) & 0xFFFF;
				int typeWord = importHdr.getShort(18) & 0xFFFF;
				int nameType = (typeWord >> 2) & 0x7;

				if (sizeOfData <= 0 || dataOffset + 20 + sizeOfData > channel.size()) continue;

				ByteBuffer strData = BinaryUtils.readAt(channel, dataOffset + 20, sizeOfData);

				if (nameType == IMPORT_ORDINAL) {
					// data = dll_name\0 (no symbol name)
					String dll = BinaryUtils.readCString(strData, 0);
					if (dllName == null && !dll.isEmpty()) dllName = dll;
					symbols.add(objects.newInstance(PeExportedSymbol.class, "#" + ordinalOrHint, ordinalOrHint));
				} else {
					// data = symbol_name\0dll_name\0
					String symName = BinaryUtils.readCString(strData, 0);
					int dllStart = symName.length() + 1;
					if (dllStart < strData.limit()) {
						String dll = BinaryUtils.readCString(strData, dllStart);
						if (dllName == null && !dll.isEmpty()) dllName = dll;
					}
					if (!symName.isEmpty()) {
						symbols.add(objects.newInstance(PeExportedSymbol.class, symName, ordinalOrHint));
					}
				}
			}

			symbols.sort(Comparator.comparing(thiz -> thiz.getName().get()));
			return objects.newInstance(SharedLibraryAbiModel.class, Optional.ofNullable(dllName), Collections.unmodifiableList(symbols));
		}
	}
}
