package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class BinaryUtils {
	private BinaryUtils() {}

	static ByteBuffer readAt(FileChannel channel, long offset, int length) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(length);
		int total = 0;
		while (total < length) {
			int n = channel.read(buf, offset + total);
			if (n == -1) throw new IOException("Unexpected end of file at offset " + (offset + total));
			total += n;
		}
		buf.flip();
		return buf;
	}

	static byte[] readBytes(FileChannel channel, long offset, int length) throws IOException {
		return readAt(channel, offset, length).array();
	}

	static String readCString(ByteBuffer buf, int offset) {
		StringBuilder sb = new StringBuilder();
		while (offset < buf.limit()) {
			byte b = buf.get(offset++);
			if (b == 0) break;
			sb.append((char) (b & 0xFF));
		}
		return sb.toString();
	}
}
