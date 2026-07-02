package dev.nokee.nativeplatform.tasks;

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
}
