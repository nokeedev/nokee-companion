package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the imported symbols of a Unix {@code ar} static archive by unioning the undefined symbols of
 * every member object (an over-approximation: we do not subtract symbols the archive defines internally).
 *
 * <p>Only the archive's own bookkeeping members — {@code "/"} (the symbol index) and {@code "//"} (the
 * long-name table) — are skipped. Every other member, <em>including</em> long-named {@code "/N"} members,
 * is a real object and must be parsed: skipping one would under-count imports and could miss a relink.
 * A member whose object format is not recognized makes the import set incomplete, so this reader throws
 * {@link IllegalArgumentException} (the classifier then disables narrowing for the whole link).
 */
final class StaticArchiveImportReader implements AbiBinaryHasher {
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n

	private static final byte ELFMAG0 = 0x7f;
	private static final byte ELFMAG1 = 'E';
	private static final byte ELFMAG2 = 'L';
	private static final byte ELFMAG3 = 'F';

	private final ElfImportReader elfReader;

	StaticArchiveImportReader(ElfImportReader elfReader) {
		this.elfReader = elfReader;
	}

	/**
	 * Reads the imports of the {@code ar} archive at the start of {@code channel} by unioning the imports
	 * of every member object. Archives do not nest — a member is an object file — so an archive is only
	 * ever read from offset 0, while each member object is read at its own data offset.
	 */
	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		byte[] magic = BinaryUtils.readBytes(channel, 0, 8);
		if (!isArMagic(magic)) {
			throw new IllegalArgumentException("not an ar archive");
		}

		long size = channel.size();
		Set<Object> imports = new LinkedHashSet<>();
		long offset = 8; // skip !<arch>\n
		while (offset + 60 <= size) {
			byte[] hdr = BinaryUtils.readBytes(channel, offset, 60);
			String name = ImportLibraryBinaryHasher.parseArMemberName(hdr);
			long memberSize = ImportLibraryBinaryHasher.parseArMemberSize(hdr);
			if (memberSize < 0) {
				break;
			}

			long dataOffset = offset + 60;
			offset = dataOffset + memberSize;
			if (memberSize % 2 != 0) {
				offset++;
			}

			// Skip only the archive's own members; "/N" is a long-named real object, not bookkeeping.
			if (name.equals("/") || name.equals("//") || name.isEmpty()) {
				continue;
			}

			imports.addAll(readMember(channel, dataOffset, memberSize));
		}
		return new StaticArchiveImportHashCode(imports);
	}

	private Set<Object> readMember(FileChannel channel, long memberOffset, long memberSize) throws IOException {
		if (memberSize < 4) {
			throw new IllegalArgumentException("archive member too small to identify");
		}
		byte[] magic = BinaryUtils.readBytes(channel, memberOffset, 4);
		if (magic[0] == ELFMAG0 && magic[1] == ELFMAG1 && magic[2] == ELFMAG2 && magic[3] == ELFMAG3) {
			return ((HasImportSymbols) elfReader.hash(channel, memberOffset, memberSize)).getImportedSymbols();
		}
		// Unrecognized member format: we cannot know its imports, so refuse to narrow.
		throw new IllegalArgumentException("unrecognized archive member object format");
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	private static final class StaticArchiveImportHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasImportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		StaticArchiveImportHashCode(Set<Object> importedSymbols) {
			entries.add(new SimpleEntry<>("symbols", importedSymbols));
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
