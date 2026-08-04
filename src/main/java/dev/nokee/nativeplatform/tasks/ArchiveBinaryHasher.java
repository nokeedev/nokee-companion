package dev.nokee.nativeplatform.tasks;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>Two conventions store a member name too long for the header's 16-byte name field, and both must be
 * unwrapped before a member can be read:
 *
 * <ul>
 *   <li><b>GNU/SysV</b> keeps a string table in the {@code "//"} member; a member named {@code "/N"} takes
 *       its name from offset {@code N} of that table. Its data is the whole member.</li>
 *   <li><b>BSD</b> — what macOS {@code ar} and {@code libtool} emit — stores the name in the first {@code N}
 *       bytes of the member <em>data</em> and writes {@code "#1/N"} in the header. Those name bytes count
 *       toward the header's size field, so the object starts {@code N} bytes into the data and is {@code N}
 *       bytes shorter; handing the reader the header-reported range lands it on the name instead of the
 *       object's magic.</li>
 * </ul>
 *
 * <p>Only the archive's own bookkeeping members are skipped: the GNU symbol index {@code "/"} and its 64-bit
 * form {@code "/SYM64/"}, the GNU long-name table {@code "//"}, and the BSD global symbol table
 * {@code "__.SYMDEF"} — which also appears as {@code "__.SYMDEF SORTED"}, {@code "__.SYMDEF_64"} and
 * {@code "__.SYMDEF_64 SORTED"}, spelled through the BSD extended-name encoding because those names do not
 * fit in the header. Every other member, <em>including</em> long-named ones, is handed to the reader:
 * skipping a real object would under-count imports and could miss a relink.
 */
final class ArchiveBinaryHasher implements AbiBinaryHasher {
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n
	private static final int HEADER_SIZE = 60;
	private static final int NAME_LENGTH = 16;
	private static final int SIZE_OFFSET = 48;
	private static final int SIZE_LENGTH = 10;
	private static final String BSD_EXTENDED_NAME = "#1/";
	private static final String BSD_SYMBOL_TABLE = "__.SYMDEF";
	private static final String GNU_SYMBOL_TABLE = "/";
	private static final String GNU_SYMBOL_TABLE_64 = "/SYM64/";
	private static final String GNU_LONG_NAME_TABLE = "//";

	private final AbiObjectHasher memberHasher;

	ArchiveBinaryHasher(AbiObjectHasher memberHasher) {
		this.memberHasher = memberHasher;
	}

	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		Set<AbiBinaryHashCode> members = new LinkedHashSet<>();
		for (Member member : membersOf(source)) {
			members.add(memberHasher.hash(source.slice(member.dataOffset, member.size)));
		}
		return new ArchiveHashCode(members);
	}

	public void visitImports(BSource source, Consumer<? super Object> visitor) throws IOException {
		for (Member member : membersOf(source)) {
			memberHasher.visitImports(source.slice(member.dataOffset, member.size), visitor);
		}
	}

	/** A real member of the archive: its resolved name and the byte range of the object it holds. */
	private static final class Member {
		private final String name;
		private final long dataOffset;
		private final long size;

		private Member(String name, long dataOffset, long size) {
			this.name = name;
			this.dataOffset = dataOffset;
			this.size = size;
		}
	}

	/**
	 * Returns the archive's real members, in file order, with extended names resolved and bookkeeping members
	 * removed. Walking stops at the first malformed header rather than throwing, so a truncated archive still
	 * yields the members that precede the damage.
	 */
	private static List<Member> membersOf(BSource source) throws IOException {
		byte[] magic = BinaryUtils.readBytes(source, 0, AR_MAGIC.length);
		if (!isArMagic(magic)) {
			throw new IllegalArgumentException("not an ar archive");
		}

		long fileSize = source.size();
		List<Member> members = new ArrayList<>();
		// The long-name table precedes the members referencing it, so a single pass can resolve every name.
		byte[] longNames = null;

		long offset = AR_MAGIC.length; // skip !<arch>\n
		while (offset + HEADER_SIZE <= fileSize) {
			byte[] hdr = BinaryUtils.readBytes(source, offset, HEADER_SIZE);
			String name = parseArMemberName(hdr);
			long memberSize = parseArMemberSize(hdr);
			if (memberSize < 0) {
				break;
			}

			long dataOffset = offset + HEADER_SIZE;
			if (dataOffset + memberSize > fileSize) {
				break;
			}
			offset = dataOffset + memberSize;
			if (memberSize % 2 != 0) {
				offset++; // members start on an even offset
			}

			if (name.equals(GNU_LONG_NAME_TABLE)) {
				longNames = readLongNameTable(source, dataOffset, memberSize);
				continue;
			}
			if (name.equals(GNU_SYMBOL_TABLE) || name.equals(GNU_SYMBOL_TABLE_64) || name.isEmpty()) {
				continue;
			}

			if (name.startsWith(BSD_EXTENDED_NAME)) {
				// The name occupies the front of the data and counts toward the member's size.
				long nameLength = parseDecimal(name.substring(BSD_EXTENDED_NAME.length()));
				if (nameLength < 0 || nameLength > memberSize) {
					continue; // malformed extended name; there is no object to read
				}
				name = trimNulls(BinaryUtils.readBytes(source, dataOffset, (int) nameLength));
				dataOffset += nameLength;
				memberSize -= nameLength;
			} else if (longNames != null && name.length() > 1 && name.charAt(0) == '/') {
				// "/N" names the member from offset N of the long-name table; an unresolvable offset keeps
				// the raw name so the member is still read rather than silently dropped.
				long tableOffset = parseDecimal(name.substring(1));
				if (tableOffset >= 0 && tableOffset < longNames.length) {
					name = longNameAt(longNames, (int) tableOffset);
				}
			} else {
				name = stripNamePadding(name);
			}

			if (name.startsWith(BSD_SYMBOL_TABLE)) {
				continue;
			}

			members.add(new Member(name, dataOffset, memberSize));
		}

		return members;
	}

	private static byte[] readLongNameTable(BSource source, long dataOffset, long size) throws IOException {
		if (size <= 0 || size > Integer.MAX_VALUE) {
			return null;
		}
		return BinaryUtils.readBytes(source, dataOffset, (int) size);
	}

	/** Reads a GNU string table entry, which runs to a {@code "/\n"} (or bare newline) terminator. */
	private static String longNameAt(byte[] table, int offset) {
		int end = offset;
		while (end < table.length && table[end] != '/' && table[end] != '\n') {
			end++;
		}
		return new String(table, offset, end - offset, StandardCharsets.US_ASCII);
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reads the name field, trimming only the space padding: the trailing {@code "/"} is what tells the
	 * special members ({@code "/"}, {@code "//"}, {@code "/N"}) apart from a plain name, so it is dropped
	 * later by {@link #stripNamePadding(String)} once the member's kind is known.
	 */
	static String parseArMemberName(byte[] hdr) {
		int end = NAME_LENGTH;
		while (end > 0 && hdr[end - 1] == ' ') end--;
		return new String(hdr, 0, end, StandardCharsets.US_ASCII);
	}

	/** Drops the {@code "/"} GNU appends to a plain name to mark where the space padding starts. */
	private static String stripNamePadding(String name) {
		if (name.endsWith("/")) {
			return name.substring(0, name.length() - 1);
		}
		return name;
	}

	private static String trimNulls(byte[] name) {
		int end = 0;
		while (end < name.length && name[end] != 0) end++;
		return new String(name, 0, end, StandardCharsets.US_ASCII);
	}

	static long parseArMemberSize(byte[] hdr) {
		return parseDecimal(new String(hdr, SIZE_OFFSET, SIZE_LENGTH, StandardCharsets.US_ASCII).trim());
	}

	/** Parses a decimal header field, returning {@code -1} rather than throwing when it is absent or malformed. */
	private static long parseDecimal(String value) {
		if (value.isEmpty()) {
			return -1;
		}
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) < '0' || value.charAt(i) > '9') {
				return -1;
			}
		}
		try {
			return Long.parseLong(value);
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
