package dev.nokee.nativeplatform.tasks;

import java.nio.ByteBuffer;

/**
 * Turns a symbol name, read directly from a (typically memory-mapped) string table, into the
 * <em>name identity</em> used both to match an imported symbol against an exported one and as the key of
 * an exported symbol in the ABI snapshot. The name bytes are consumed straight from the buffer so callers
 * never build an intermediate object; the strategy decides the representation.
 *
 * <p>The identity is used for <em>discovery</em> (which exported symbols are actually imported): a
 * collision only ever causes an exported symbol to be retained when it was not truly imported — an
 * over-approximation that may trigger a spurious relink but can never miss one. It therefore does
 * <em>not</em> need to be collision-free, only fast. The default {@link #raw()} strategy returns the name
 * itself (collision-free); a future strategy may fold long (e.g. mangled C++) names into a compact
 * {@code HashCode}, trading collision-freedom for memory. Either representation is directly snapshottable
 * as a build-cache input.
 */
interface SymbolNameHasher {
	/**
	 * Computes the name identity of the NUL-terminated name starting at {@code offset} in
	 * {@code nameTable}, scanning no further than {@code end} (which also bounds a name whose terminator
	 * is missing).
	 *
	 * @param nameTable  the string table, read via absolute {@code get(index)} so a mapped buffer works
	 * @param offset  index of the first byte of the name
	 * @param end  exclusive upper bound on the scan (e.g. the string table limit)
	 * @return the identity; equal names must produce equal identities
	 */
	Object identity(ByteBuffer nameTable, int offset, int end);

	/**
	 * {@return a strategy that returns the raw name as a {@link String} (collision-free)}
	 */
	static SymbolNameHasher raw() {
		return (nameTable, offset, end) -> {
			int i = offset;
			while (i < end && nameTable.get(i) != 0) {
				i++;
			}
			byte[] name = new byte[i - offset];
			for (int j = 0; j < name.length; j++) {
				name[j] = nameTable.get(offset + j);
			}
			return new String(name);
		};
	}
}
