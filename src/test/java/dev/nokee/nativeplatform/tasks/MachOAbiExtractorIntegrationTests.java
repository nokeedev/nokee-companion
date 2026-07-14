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
 * Integration tests for Mach-O ABI extraction via NativeLibraryAbiExtractor.
 *
 * Prebuilt binaries live in src/test/resources/fixtures/. See each
 * fixture directory's BUILD file for the commands to produce them.
 */
class MachOAbiExtractorIntegrationTests {
	private static final MachOBinaryHasher reader = new MachOBinaryHasher();

	private static AbiBinaryHasher.AbiBinaryHashCode extract(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			return reader.hash(channel);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractDylibWithNamedExports(String arch) throws IOException {
		AbiBinaryHasher.AbiBinaryHashCode model = extract(fixture("named-exports/" + arch + "/libnamed.dylib"));
		assertThat(model, is(sharedLibrary(
			strongMachOSymbol("_compute"),
			strongMachOSymbol("_greet"),
			strongMachOSymbol("_value")
		)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractDylibWithNoExports(String arch) throws IOException {
		AbiBinaryHasher.AbiBinaryHashCode model = extract(fixture("no-exports/" + arch + "/libno_exports.dylib"));
		assertThat(model, is(emptySharedLibrary()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractDylibDistinguishesWeakFromStrongSymbols(String arch) throws IOException {
		AbiBinaryHasher.AbiBinaryHashCode model = extract(fixture("weak-symbols/" + arch + "/libweak.dylib"));
		assertThat(model, is(sharedLibrary(
			strongMachOSymbol("_strong_func"),
			strongMachOSymbol("_strong_var"),
			weakMachOSymbol("_weak_func"),
			weakMachOSymbol("_weak_var")
		)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractStaticArchiveReturnsStaticLibraryModel(String arch) throws IOException {
		assertThat(() -> extract(fixture("static-archive/" + arch + "/libstatic.a")), throwsException(instanceOf(IllegalArgumentException.class)));
	}

	private static Path fixture(String relativePath) {
		try {
			return Paths.get(MachOAbiExtractorIntegrationTests.class
				.getResource("/fixtures/" + relativePath).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found: " + relativePath
				+ " — build it per the BUILD file", e);
		}
	}
}
