package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.AbiBinaryHashCode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.function.Consumer;

/**
 * Hashes one object image occupying {@code [base, base + size)} of a channel — a standalone object file
 * ({@code base == 0}) or a single member of an archive (base at the member's data offset). Used by
 * {@link ArchiveBinaryHasher} so the archive walk makes no assumption about what its members are: the
 * injected reader is what figures out whether a member is an ELF object, a COFF object or an import stub,
 * and what to extract from it.
 */
interface AbiObjectHasher {
	AbiBinaryHashCode hash(BSource source) throws IOException;

	void visitImports(BSource source, Consumer<? super Object> visitor) throws IOException;
}
