package dev.nokee.companion.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class PathExtensions {
	public static void write(Path self, String content) {
		write(self, content.getBytes(StandardCharsets.UTF_8));
	}

	public static Path write(Path self, byte[] bytes) {
		try {
			if (Files.exists(self) && Files.isDirectory(self)) {
				throw new IOException("not a file");
			}

			Files.createDirectories(self.getParent());
			Files.write(self, bytes);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return self;
	}

	public static Path append(Path self, String content) {
		return append(self, content.getBytes(StandardCharsets.UTF_8));
	}

	public static Path append(Path self, byte[] bytes) {
		try {
			if (Files.exists(self) && Files.isDirectory(self)) {
				throw new IOException("not a file");
			}

			Files.createDirectories(self.getParent());
			Files.write(self, bytes, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return self;
	}
}
