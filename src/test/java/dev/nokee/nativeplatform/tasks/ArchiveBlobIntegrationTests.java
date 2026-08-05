package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class ArchiveBlobIntegrationTests {
	private static void blob(Path path, Consumer<? super ArchiveBlob> action) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			action.accept(ArchiveBlob.parse(new BSource(channel)));
		}
	}

	@Test
	void nullDevice() throws IOException {
		blob(fixture("dev-null.a"), it -> {
			var iter = it.members().iterator();

			var e0 = iter.next();
			assertThat(e0.identifier(), equalTo("null"));
			assertThat(e0.file().size(), is(0L));

			assertThat(iter.hasNext(), is(false));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "llvm", "gnu" })
	void shortNames(String standard) throws IOException {
		blob(fixture(standard + "-short-names.a"), it -> {
			var iter = it.members().iterator();
			assertThat(iter.hasNext(), is(true));

			var e0 = iter.next();
			assertThat(e0.identifier(), equalTo("a"));
			assertThat(toString(e0.file()), is("file with As\n"));

			var e1 = iter.next();
			assertThat(e1.identifier(), equalTo("bb"));
			assertThat(toString(e1.file()), is("file with Bs\n"));

			var e2 = iter.next();
			assertThat(e2.identifier(), equalTo("ccc"));
			assertThat(toString(e2.file()), is("file with Cs\n"));

			assertThat(iter.hasNext(), is(false));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "gnu", "llvm" })
	void longNames(String standard) throws IOException {
		blob(fixture(standard + "-long-names.a"), it -> {
			var iter = it.members().iterator();
			assertThat(iter.hasNext(), is(true));

			var e0 = iter.next();
			assertThat(e0.identifier(), equalTo("a-17-chars-file.t"));
			assertThat(toString(e0.file()), is("a one char too long filename\n"));

			var e1 = iter.next();
			assertThat(e1.identifier(), equalTo("another-long-file-name.txt"));
			assertThat(toString(e1.file()), is("another long filename\n"));

			assertThat(iter.hasNext(), is(false));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "gnu", "llvm" })
	void nameWithSpaces(String standard) throws IOException {
		blob(fixture(standard + "-name-with-spaces.a"), it -> {
			var iter = it.members().iterator();
			assertThat(iter.hasNext(), is(true));

			var e0 = iter.next();
			assertThat(e0.identifier(), equalTo("short spaces.t"));
			assertThat(toString(e0.file()), is("a short filename with a space\n"));

			var e1 = iter.next();
			assertThat(e1.identifier(), equalTo("long name with spaces.txt"));
			assertThat(toString(e1.file()), is("a long filename with spaces\n"));

			assertThat(iter.hasNext(), is(false));
		});
	}

	private static String toString(BSource s) {
		ByteBuffer buf = ByteBuffer.allocate((int) s.size());
		s.read(buf);
		return new String(buf.array(), StandardCharsets.UTF_8);
	}

	private static Path fixture(String relativePath) {
		try {
			return Paths.get(ArchiveBlobIntegrationTests.class
				.getResource("/fixtures/archive-blob/" + relativePath.trim()).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found: " + relativePath + " — build it per the BUILD file", e);
		}
	}
}
