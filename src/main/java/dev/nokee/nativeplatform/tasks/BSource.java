package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class BSource {
	private final FileChannel channel;
	private final long offset;

	public BSource(FileChannel channel) {
		this(channel, 0);
	}

	public BSource(FileChannel channel, long offset) {
		this.channel = channel;
		this.offset = offset;
	}

	public long size() {
		try {
			return channel.size() - offset;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public int read(ByteBuffer dst) {
		try {
			return channel.read(dst, offset);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public ByteBuffer read(long len) {
		ByteBuffer result = ByteBuffer.allocate((int) len);
		int size = read(result);
		result.limit(size);
		return result;
	}

	public int read(ByteBuffer dst, long position) {
		try {
			return channel.read(dst, offset + position);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public MappedByteBuffer mmap(long position, long size) {
		try {
			return channel.map(FileChannel.MapMode.READ_ONLY, offset + position, size);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public BSource slice(long dataOffset, long size) {
		return new BSource(channel, offset + dataOffset) {
			@Override
			public long size() {
				return size;
			}
		};
	}
}
