package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Walks the members of a Unix {@code ar} archive once and applies an injected {@link AbiObjectHasher} to
 * each member, returning a composite {@link AbiBinaryHashCode} that is the set of its members' hash codes.
 * It bakes in no assumption about the archive's kind: the injected reader is what decides whether a member
 * is an ELF object, a COFF object or an import stub, and a consumer traverses the members to read whatever
 * each exposes ({@link HasImportSymbols} for a static library's objects, {@link HasExportSymbols} for an
 * import library's stubs).
 *
 * <p>Only the archive's own bookkeeping members — {@code "/"} (the symbol index) and {@code "//"} (the
 * long-name table) — are skipped. Every other member, <em>including</em> long-named {@code "/N"} members,
 * is handed to the reader: skipping a real object would under-count imports and could miss a relink.
 */
final class ArchiveBinaryHasher implements AbiBinaryHasher {
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n

	private final AbiObjectHasher memberHasher;

	ArchiveBinaryHasher(AbiObjectHasher memberHasher) {
		this.memberHasher = memberHasher;
	}

	@Override
	public AbiBinaryHashCode hash(FileChannel channel) throws IOException {
		byte[] magic = BinaryUtils.readBytes(channel, 0, 8);
		if (!isArMagic(magic)) {
			throw new IllegalArgumentException("not an ar archive");
		}

		long size = channel.size();
		Set<AbiBinaryHashCode> members = new LinkedHashSet<>();
		long offset = 8; // skip !<arch>\n
		while (offset + 60 <= size) {
			byte[] hdr = BinaryUtils.readBytes(channel, offset, 60);
			String name = parseArMemberName(hdr);
			long memberSize = parseArMemberSize(hdr);
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

			members.add(memberHasher.hash(channel, dataOffset, memberSize));
		}

		return new ArchiveHashCode(members);
	}

	public void visitImports(FileChannel channel, Consumer<? super Object> visitor) throws IOException {
		byte[] magic = BinaryUtils.readBytes(channel, 0, 8);
		if (!isArMagic(magic)) {
			throw new IllegalArgumentException("not an ar archive");
		}

		long size = channel.size();
		long offset = 8; // skip !<arch>\n
		while (offset + 60 <= size) {
			byte[] hdr = BinaryUtils.readBytes(channel, offset, 60);
			String name = parseArMemberName(hdr);
			long memberSize = parseArMemberSize(hdr);
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

			memberHasher.visitImports(channel, dataOffset, memberSize, visitor);
		}
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) {
				return false;
			}
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

	private static final class ArchiveHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasMembers {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		ArchiveHashCode(Set<AbiBinaryHashCode> members) {
			assert members.stream().allMatch(it -> it.type() == Type.OBJECT_FILE);
			entries.add(new SimpleEntry<>("members", members));
		}

		@Override
		public Type type() {
			return Type.STATIC_LIB;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<AbiBinaryHashCode> getMembers() {
			return (Set<AbiBinaryHashCode>) get("members");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}
}
