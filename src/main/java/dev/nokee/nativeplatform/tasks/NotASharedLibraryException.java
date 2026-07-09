package dev.nokee.nativeplatform.tasks;

/**
 * Thrown by an {@link AbiModelReader} when the source is of the expected container format but does
 * not represent a shared library that exposes an ABI (for example a non-{@code ET_DYN} ELF, a
 * Mach-O that is not a dylib, or an {@code ar} archive that is a plain static library rather than a
 * Windows import library). The magic-aware extractor maps this to a {@code null} model.
 */
final class NotASharedLibraryException extends RuntimeException {
	NotASharedLibraryException(String message) {
		super(message);
	}
}
