package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.throwsException;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ELF ABI extraction via NativeLibraryAbiExtractor.
 *
 * Prebuilt binaries live in src/test/resources/fixtures/. See each
 * fixture directory's BUILD file for the commands to produce them.
 */
class ElfAbiExtractorIntegrationTests {
	private static final ElfAbiModelReader reader = new ElfAbiModelReader();

	private static AbiModel extract(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			return reader.hash(channel);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryWithNamedExports(String arch) throws IOException {
		AbiModel model = extract(fixture("named-exports/" + arch + "/libnamed.so"));
		assertThat(model, is(sharedLibrary(
			strongElfSymbol("greet"),
			strongElfSymbol("value"),
			strongElfSymbol("compute")
		)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryWithNoExports(String arch) throws IOException {
		AbiModel model = extract(fixture("no-exports/" + arch + "/libno_exports.so"));
		assertThat(model, is(emptySharedLibrary()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryDistinguishesWeakFromStrongSymbols(String arch) throws IOException {
		AbiModel model = extract(fixture("weak-symbols/" + arch + "/libweak.so"));
		assertThat(model, is(sharedLibrary(
			weakElfSymbol("weak_var"),
			strongElfSymbol("strong_var"),
			strongElfSymbol("strong_func"),
			weakElfSymbol("weak_func")
		)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractStaticArchiveReturnsStaticLibraryModel(String arch) throws IOException {
		assertThat(() -> extract(fixture("static-archive/" + arch + "/libstatic.a")), throwsException(instanceOf(IllegalArgumentException.class)));
	}

	private static Path fixture(String relativePath) {
		try {
			return Paths.get(ElfAbiExtractorIntegrationTests.class
				.getResource("/fixtures/" + relativePath).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found: " + relativePath
				+ " — build it per the BUILD file", e);
		}
	}
}
