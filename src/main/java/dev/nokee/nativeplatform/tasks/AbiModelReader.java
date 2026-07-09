package dev.nokee.nativeplatform.tasks;

import java.io.IOException;

/**
 * Reads an {@link AbiModel} from a binary wrapped at construction. A reader is bound to a single
 * source and validates its own magic, so it can be used standalone. It never returns {@code null}:
 * it returns a model, throws {@link IllegalArgumentException} when handed the wrong kind of file
 * (magic mismatch), or throws {@link NotASharedLibraryException} when the file is of the right
 * format but exports no shared-library ABI.
 */
interface AbiModelReader {
	AbiModel read() throws IOException;
}
