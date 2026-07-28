package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class ImportLibraryBinaryHasher implements AbiBinaryHasher {
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n
	// NameType=0 means IMPORT_ORDINAL (no symbol name in data); represented as "#<ordinal>"
	private static final int IMPORT_ORDINAL = 0;

	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		byte[] magic = BinaryUtils.readBytes(channel, 0, 8);
		if (!isArMagic(magic)) {
			throw new IllegalArgumentException("not an ar archive");
		}
		if (!isWindowsImportLibrary(channel)) {
			throw new NotASharedLibraryException("ar archive is not a Windows import library");
		}
		return parse(channel);
	}

	private boolean isWindowsImportLibrary(FileChannel channel) throws IOException {
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

	private AbiBinaryHashCode parse(FileChannel channel) throws IOException {
		long offset = 8; // skip !<arch>\n
		String dllName = null;
		Set<ExportedSymbol> symbols = new LinkedHashSet<>();

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

			ByteBuffer strData = BinaryUtils.readAt(channel, dataOffset + 20, sizeOfData); // TODO: Use mmap

			if (nameType == IMPORT_ORDINAL) {
				// data = dll_name\0 (no symbol name)
				String dll = BinaryUtils.readCString(strData, 0);
				if (dllName == null && !dll.isEmpty()) dllName = dll;
				symbols.add(new PEExportedSymbol(null, ordinalOrHint));
			} else {
				// data = symbol_name\0dll_name\0
				String symName = BinaryUtils.readCString(strData, 0);
				int dllStart = symName.length() + 1;
				if (dllStart < strData.limit()) {
					String dll = BinaryUtils.readCString(strData, dllStart);
					if (dllName == null && !dll.isEmpty()) dllName = dll;
				}
				if (!symName.isEmpty()) {
					symbols.add(new PEExportedSymbol(symName, ordinalOrHint));
				}
			}
		}

		return new PEHashCode(dllName, symbols);
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) return false;
		}
		return true;
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

	private static final class PEExportedSymbol extends AbstractMap<String, Object> implements ExportedSymbol {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public PEExportedSymbol(String name, int ordinalOrHint) {
			entries.add(new SimpleEntry<>("name", name));
			entries.add(new SimpleEntry<>("ordinalOrHint", ordinalOrHint));
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

	private static final class PEHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasExportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		public PEHashCode(String dllName, Set<ExportedSymbol> symbols) {
			entries.add(new SimpleEntry<>("dllName", dllName));
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
			return new PEHashCode((String) get("dllName"), retained);
		}
	}
}
