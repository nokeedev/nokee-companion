package dev.nokee.nativeplatform.tasks;

/**
 * Thrown by an {@link AbiBinaryHasher} when the source <em>is</em> a shared library (right container,
 * right kind) but its exported ABI cannot be read — for example an ELF with stripped section headers or
 * an unreadable {@code .dynsym}. Unlike {@link NotASharedLibraryException}, the file is known to be a
 * shared library, so the extractor byte-snapshots the whole file (and narrowing stays enabled for the
 * other libraries) rather than treating it as an unclassifiable input.
 */
final class UnreadableSharedLibraryException extends RuntimeException {
	UnreadableSharedLibraryException(String message) {
		super(message);
	}
}
