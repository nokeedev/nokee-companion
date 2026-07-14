package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.HashCode;

import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Hashes an {@link AbiBinaryHashCode} out of a binary. A reader is stateless and reusable: a single instance
 * can hash many channels. It validates its own magic, so it can be used standalone. It never returns
 * {@code null}: it returns a model, throws {@link IllegalArgumentException} when handed the wrong
 * kind of file (magic mismatch), or throws {@link NotASharedLibraryException} when the file is of
 * the right format but exports no shared-library ABI.
 */
interface AbiBinaryHasher {
	AbiBinaryHashCode hash(FileChannel channel) throws IOException;

	interface AbiBinaryHashCode {
		HashCode getExportedSymbols();
	}
}
