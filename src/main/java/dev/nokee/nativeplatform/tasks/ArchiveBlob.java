package dev.nokee.nativeplatform.tasks;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.function.Consumer;

import static dev.nokee.nativeplatform.tasks.BinaryUtils.requireInt;
import static java.nio.charset.StandardCharsets.US_ASCII;

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
// TODO: implements BinaryBlob common interface
final class ArchiveBlob {
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n
	private static final int HEADER_SIZE = 60; // in bytes
	private static final int NAME_LENGTH = 16; // in bytes
	private static final int SIZE_OFFSET = 48; // in bytes
	private static final int SIZE_LENGTH = 10; // in bytes
	// TODO: Write an utility Consumer that will automatically skip all BSD/GNU symbol table to be used with forEach(...)
	//   Note that we also need tests that would include those symbol tables
	private static final String BSD_SYMBOL_TABLE = "__.SYMDEF";
	private static final String GNU_SYMBOL_TABLE = "/";
	private static final String GNU_SYMBOL_TABLE_64 = "/SYM64/";
	public static Consumer<ArchiveMember> skipSymbolTables(Consumer<? super ArchiveMember> consumer) {
		return it -> {
			String name = it.identifier();
			if (name.equals(BSD_SYMBOL_TABLE)) {
				// ignores
			} else if (name.equals(GNU_SYMBOL_TABLE) || name.equals(GNU_SYMBOL_TABLE_64)) {
				// ignores
			} else {
				consumer.accept(it);
			}
		};
	}

	ArchiveBlob(BSource source, ByteBuffer hdr) {
		this.source = source;
		this.hdr = hdr;
	}

	public static boolean isArMagic(byte[] h) {
		if (h.length < AR_MAGIC.length) return false;
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) return false;
		}
		return true;
	}

	private final BSource source;
	private final ByteBuffer hdr;

	public static ArchiveBlob parse(BSource source) {
		ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE).limit(AR_MAGIC.length);
		source.read(hdr);
		if (!isArMagic(hdr.array())) {
			throw new IllegalArgumentException("not an ar archive");
		}
		return new ArchiveBlob(source, hdr.clear());
	}

	public Iterable<ArchiveMember> members() {
		return new Iterable<ArchiveMember>() {
			@Override
			public Iterator<ArchiveMember> iterator() {
				return new Iterator<ArchiveMember>() {
					private long position = AR_MAGIC.length;
					private GnuLongNameTable lnt;

					@Override
					public boolean hasNext() {
						return position < source.size();
					}

					@Override
					public ArchiveMember next() {
						int length = source.read(hdr.clear(), position);
						if (length == -1) throw new NoSuchElementException();

						assert length == HEADER_SIZE : "unexpected file header size";
						assert hdr.get(58) == 0x60 && hdr.get(59) == 0x0A : "invalid ending characters";

						// 1) find size of extended name or offset in long names table
						long extendedSize = findBsdExtendedNameSize(hdr); // zero means no extended name
						long lntOffset = GnuLongNameTable.findOffset(hdr);

						// 2) file size
						long size = parseFileSize(hdr) - extendedSize;

						// 3) compute start of file blob
						long start = HEADER_SIZE + extendedSize;

						// 4) move position to end of file blob
						long memberPos = position;
						position += start + size;
						position += position % 2; // archive members are even-byte aligned (see System V ABI, Generic ABI (gABI), “Archive File” section)

						// 5) track long names table (and skip
						if (lnt == null && GnuLongNameTable.isGnuLongNameTable(hdr)) {
							lnt = new GnuLongNameTable(source.mmap(memberPos + start, size));
							return next();
						}

						// 6) return member containing
						return new ArchiveMember() {
							@Override
							public BSource file() {
								return source.slice(memberPos + start, size);
							}

							@Override
							public String identifier() {
								if (lntOffset != -1) {
									return lnt.read(requireInt(lntOffset));
								} else if (extendedSize == 0) {
									// TODO: Do not use hdr in case keep a copy of the object, note that all object cannot survive the FileChannel being closed
									return newString(hdr.array(), 0, gnutrim(hdr.array(), 0, NAME_LENGTH));
								} else {
									return readExtName(requireInt(extendedSize));
								}
							}

							private String readExtName(int length) {
								ByteBuffer buf = ByteBuffer.allocate(length);
								int len = trimNulls(buf.array(), 0, source.read(buf, memberPos + HEADER_SIZE));
								return newString(buf.array(), 0, len);
							}
						};
					}
				};
			}
		};
	}

	/** Returns length without tailing spaces (AR). */
	private static int artrim(byte[] buf, int off, int len) {
		int end = off + len - 1;

		// We assume that short name will be longer than 8 bytes,
		//   By definition there is an overhead of 2-4 bytes for the extension.
		//   That leaves 4-6 bytes for the whole file name before it becomes more performant
		//   to read from the end. The chance of that happening is quite slim. Let's read
		//   from the end, backward.
		while (end > off && buf[end] == 0x20) end--;

		return end + 1;
	}

	/** Returns length without tailing spaces (see {@link #artrim(byte[], int, int)}) and forward slash (GNU) */
	private static int gnutrim(byte[] buf, int off, int len) {
		int end = artrim(buf, off, len) - 1;

		// If // -> do nothing
		// If a/ -> a
		// If foo.o/ -> foo.o
		// If foo.o -> foo.o
		if (buf[end] == '/') {
			if (end > 1) end--;
			else if (buf[0] != '/') end--;
		}

		return end + 1;
	}

	/** Returns length without padding null bytes. */
	private static int trimNulls(byte[] buf, int off, int len) {
		int end = off + len - 1;

		// There's very little padding so reading from the end is more efficient.
		while (end >= off && buf[end] == 0x00) end--;

		return end + 1;
	}

	private static String newString(byte[] buf, int off, int len) {
		return new String(buf, off, len, US_ASCII);
	}

	private static final class GnuLongNameTable {
		private final MappedByteBuffer lnt;

		private GnuLongNameTable(MappedByteBuffer lnt) {
			this.lnt = lnt;
		}

		/** Returns {@literal true} if the identifier (name) is the GNU Long Name Table, or {@literal false} otherwise. */
		private static boolean isGnuLongNameTable(ByteBuffer buffer) {
			byte[] buf = buffer.array();
			return buf[0] == '/' && buf[1] == '/' && buf[2] == ' ';
		}

		/** Returns offset into GNU Long Name Table (i.e. /N) or -1 otherwise. */
		public static long findOffset(ByteBuffer buffer) {
			byte[] buf = buffer.array();
			int i;
			int state = 0; // basic state machine
			int lntSizeBegin = -1;
			for (i = 0; i < NAME_LENGTH && buf[i] != 0x20; ++i) {
				switch (state) {
					case 0: // initial state
						if (buf[i] == '/') state = 1;
						else return -1; // not long name
						break;
					case 1: // found '/'
						if (buf[i] != '/') {
							state = 2;
							lntSizeBegin = i;
						}
						else return -1; // not long name
						break;
				}
			}

			if (state == 1) { // TODO: Add test for this case
				return -1;
			}
			return parseDecimal(buf, lntSizeBegin, i - lntSizeBegin);
		}

		/** Reads a GNU string table entry, which runs to a {@code "/\n"} (or bare newline) terminator. */
		public String read(int offset) {
			int end = offset;
			while (end < lnt.capacity() && lnt.get(end) != '/' && lnt.get(end) != '\n') {
				end++;
			}
			byte[] b = new byte[end - offset];
			lnt.get(offset, b);
			return newString(b, 0, b.length);
		}
	}

	/** Parse decimal value directly from specified buffer. */
	private static long parseDecimal(byte[] buf, int off, int len) {
		// Theoretically, decimal values can be of the following size:
		//   - File identifier: 13-15 decimals (requires long)
		//   - File modification: 12 decimals (requires long)
		//   - Owner ID: 6 decimals (requires int)
		//   - Group ID: 6 decimals (requires int)
		//   - File size: 10 decimals (requires long)
		return Long.parseLong(newString(buf, off, len));
	}

	private static long parseFileSize(ByteBuffer buffer) {
		byte[] buf = buffer.array();
		int end = SIZE_OFFSET;
		while (end < SIZE_OFFSET + SIZE_LENGTH && buf[end] != 0x20) end++;
		return parseDecimal(buf, SIZE_OFFSET, end - SIZE_OFFSET);
	}

	/** Find BSD Extended Name size, i.e. #1/N. */
	private static long findBsdExtendedNameSize(ByteBuffer buffer) {
		byte[] buf = buffer.array();
		int i;
		int state = 0; // basic state machine
		int extendedSizeBegin = -1;
		for (i = 0; i < NAME_LENGTH && buf[i] != 0x20; ++i) {
			switch (state) {
				case 0: // initial state
					if (buf[i] == '#') state = 1;
					else return 0; // not extended name
					break;
				case 1: // found '#'
					if (buf[i] == '1') state = 2;
					else return 0; // not extended name
					break;
				case 2: // found '#1'
					if (buf[i] == '/') state = 3;
					else return 0; // not extended name
					break;
				case 3: // found '#1/'
					extendedSizeBegin = i;
					state = 4; // cumulate extended name size
					break;
			}
		}

		return parseDecimal(buf, extendedSizeBegin, i - extendedSizeBegin);
	}

	public interface ArchiveMember {
		BSource file();
		String identifier();
	}
}
