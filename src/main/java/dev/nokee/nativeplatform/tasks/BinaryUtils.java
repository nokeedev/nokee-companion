package dev.nokee.nativeplatform.tasks;

import org.gradle.internal.hash.PrimitiveHasher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class BinaryUtils {
	private BinaryUtils() {}

	static ByteBuffer readAt(FileChannel channel, long offset, int length) throws IOException {
		return readInto(channel, offset, ByteBuffer.allocate(length), length);
	}

	/**
	 * Reads {@code length} bytes at {@code offset} into {@code buf}, reusing the caller-supplied buffer instead of
	 * allocating a new one. The buffer's byte order is preserved. Intended for tight loops where a fresh buffer
	 * would otherwise be allocated per iteration; callers must not retain the buffer's contents across calls.
	 */
	static ByteBuffer readInto(FileChannel channel, long offset, ByteBuffer buf, int length) throws IOException {
		buf.clear();
		buf.limit(length);
		long pos = offset;
		while (buf.hasRemaining()) {
			int n = channel.read(buf, pos);
			if (n == -1) throw new IOException("Unexpected end of file at offset " + pos);
			pos += n;
		}
		buf.flip();
		return buf;
	}

	static byte[] readBytes(FileChannel channel, long offset, int length) throws IOException {
		return readAt(channel, offset, length).array();
	}

	static String readCString(ByteBuffer buf, int offset) {
		byte[] b = buf.array();
		int length = 0;
		for (int i = offset; i < buf.limit() && b[i] != 0; ++i) {
			length++;
		}
		return new String(b, offset, length);
	}

	static int hashCString(PrimitiveHasher hasher, ByteBuffer buf, int offset) {
		byte[] b = buf.array();
		int length = 0;
		for (int i = offset; i < buf.limit() && b[i] != 0; ++i) {
			length++;
		}
		if (length > 0) {
			hasher.putBytes(b, offset, length);
		}
		return length;
	}

	/**
	 * Hashes the NUL-terminated string at {@code offset} directly from the channel, reading only as
	 * much as the string needs (in chunks through the reused {@code scratch} buffer) instead of
	 * loading the enclosing string table. Reads never pass {@code endOffset} (the string table end),
	 * which also bounds a string whose terminator is missing. Returns the string length in bytes.
	 * Fed byte-for-byte into the hasher, so the result matches {@link #hashCString} for the same name.
	 */
	static int hashCStringAt(PrimitiveHasher hasher, FileChannel channel, ByteBuffer scratch, long offset, long endOffset) throws IOException {
		int length = 0;
		long pos = offset;
		while (pos < endOffset) {
			scratch.clear();
			scratch.limit((int) Math.min(scratch.capacity(), endOffset - pos));
			int n = channel.read(scratch, pos);
			if (n <= 0) break;
			byte[] b = scratch.array();
			for (int i = 0; i < n; i++) {
				if (b[i] == 0) {
					if (i > 0) hasher.putBytes(b, 0, i);
					return length + i;
				}
			}
			hasher.putBytes(b, 0, n);
			length += n;
			pos += n;
		}
		return length;
	}

	/**
	 * Reads the NUL-terminated string at {@code offset} directly from the channel, without loading the
	 * enclosing string table. Reads are bounded by {@code endOffset} (the string table end).
	 */
	static String readCStringAt(FileChannel channel, long offset, long endOffset) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer buf = ByteBuffer.allocate(256);
		long pos = offset;
		while (pos < endOffset) {
			buf.clear();
			buf.limit((int) Math.min(buf.capacity(), endOffset - pos));
			int n = channel.read(buf, pos);
			if (n <= 0) break;
			byte[] b = buf.array();
			for (int i = 0; i < n; i++) {
				if (b[i] == 0) {
					out.write(b, 0, i);
					return new String(out.toByteArray());
				}
			}
			out.write(b, 0, n);
			pos += n;
		}
		return new String(out.toByteArray());
	}
}
