package dev.nokee.nativeplatform.tasks;

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
import java.util.function.Consumer;

/**
 * Reads the undefined external symbols of a COFF object ({@code .obj}) from its symbol table — the
 * references a linker resolves against. COFF has no strong magic, so it validates the machine type; a
 * short-import record (machine {@code 0x0000}) or any unrecognized machine is rejected with an
 * {@link IllegalArgumentException}. Reads the object image at {@code [base, base + size)} so a standalone
 * object and an archive member are handled the same way. COFF is always little-endian.
 */
final class CoffImportReader implements AbiBinaryHasher {
	private static final int IMAGE_SYM_UNDEFINED = 0;
	private static final int IMAGE_SYM_CLASS_EXTERNAL = 2;
	private static final int SYMBOL_SIZE = 18;

	@Override
	public AbiBinaryHashCode hash(BSource source) throws IOException {
		if (source.size() < 20) {
			throw new IllegalArgumentException("not a COFF object");
		}
		ByteBuffer hdr = BinaryUtils.readAt(source, 0, 20);
		hdr.order(ByteOrder.LITTLE_ENDIAN);
		int machine = hdr.getShort(0) & 0xFFFF;
		if (!isKnownMachine(machine)) {
			throw new IllegalArgumentException("not a COFF object (machine=0x" + Integer.toHexString(machine) + ")");
		}

		long symTableOffset = hdr.getInt(8) & 0xFFFFFFFFL;
		int numberOfSymbols = hdr.getInt(12);
		if (symTableOffset == 0 || numberOfSymbols <= 0) {
			return new CoffImportHashCode(Collections.emptySet());
		}

		long symBase = symTableOffset;
		long strBase = symBase + (long) numberOfSymbols * SYMBOL_SIZE;
		long memberEnd = source.size();

		Set<Object> imports = new LinkedHashSet<>();
		ByteBuffer sym = ByteBuffer.allocate(SYMBOL_SIZE);
		sym.order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < numberOfSymbols; i++) {
			BinaryUtils.readInto(source, symBase + (long) i * SYMBOL_SIZE, sym, SYMBOL_SIZE);
			int value = sym.getInt(8);
			int sectionNumber = sym.getShort(12); // signed; 0 == IMAGE_SYM_UNDEFINED
			int storageClass = sym.get(16) & 0xFF;
			int numberOfAuxSymbols = sym.get(17) & 0xFF;

			// Undefined external with value 0 is an import; value != 0 is a common symbol (tentative definition).
			if (sectionNumber == IMAGE_SYM_UNDEFINED && storageClass == IMAGE_SYM_CLASS_EXTERNAL && value == 0) {
				String name = readSymbolName(source, sym, strBase, memberEnd);
				if (!name.isEmpty()) {
					imports.add(name);
				}
			}

			i += numberOfAuxSymbols; // auxiliary records follow their symbol and are not symbols themselves
		}
		return new CoffImportHashCode(imports);
	}

	private static String readSymbolName(BSource source, ByteBuffer symbol, long strBase, long endOffset) throws IOException {
		// The 8-byte name is either an inline (NUL-padded) short name, or, when its first 4 bytes are zero,
		// a 4-byte offset into the string table that follows the symbol table.
		if (symbol.getInt(0) == 0) {
			long offset = symbol.getInt(4) & 0xFFFFFFFFL;
			return BinaryUtils.readCStringAt(source, strBase + offset, endOffset);
		}
		byte[] name = new byte[8];
		int length = 0;
		while (length < 8 && symbol.get(length) != 0) {
			name[length] = symbol.get(length);
			length++;
		}
		return new String(name, 0, length, StandardCharsets.US_ASCII);
	}

	private static boolean isKnownMachine(int machine) {
		switch (machine) {
			case 0x014c: // I386
			case 0x8664: // AMD64 (x64)
			case 0xaa64: // ARM64
			case 0x01c0: // ARM
			case 0x01c4: // ARMNT (Thumb-2)
			case 0x0200: // IA64
			case 0x5032: // RISC-V 32
			case 0x5064: // RISC-V 64
			case 0x6232: // LoongArch 32
			case 0x6264: // LoongArch 64
				return true;
			default:
				return false;
		}
	}

	private static final class CoffImportHashCode extends AbstractMap<String, Object> implements AbiBinaryHashCode, HasImportSymbols {
		private final Set<Entry<String, Object>> entries = new LinkedHashSet<>();

		CoffImportHashCode(Set<Object> importedSymbols) {
			entries.add(new SimpleEntry<>("symbols", importedSymbols));
		}

		@Override
		public Type type() {
			return Type.OBJECT_FILE;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<Object> getImportedSymbols() {
			return (Set<Object>) get("symbols");
		}

		@Override
		public @NotNull Set<Entry<String, Object>> entrySet() {
			return entries;
		}
	}
}
