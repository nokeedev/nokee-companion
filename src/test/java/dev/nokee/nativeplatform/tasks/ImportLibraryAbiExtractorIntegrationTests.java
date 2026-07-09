package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

import static dev.nokee.nativeplatform.tasks.AbiMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Windows import library (.lib) ABI extraction via
 * NativeLibraryAbiExtractor.
 *
 * Prebuilt binaries live in src/test/resources/fixtures/. See each
 * fixture directory's BUILD file for the commands to produce them.
 */
@Disabled
class ImportLibraryAbiExtractorIntegrationTests {
	private static AbiModel extract(Path path) throws IOException {
		try (ImportLibraryAbiModelReader reader = new ImportLibraryAbiModelReader(FileChannel.open(path))) {
			return reader.read();
		}
	}

	@Test
	void extractImportLibraryWithNamedExports() throws IOException {
		AbiModel model = extract(fixture("named-exports/named.lib"));
		assertThat(model, is(sharedLibrary(hasItems(
			namedPeSymbol("compute"),
			namedPeSymbol("greet"),
			namedPeSymbol("value")
		))));
	}

	@Test
	void extractImportLibraryWithNoExports() throws IOException {
		AbiModel model = extract(fixture("no-exports/no_exports.lib"));
		assertThat(model, is(emptySharedLibrary()));
	}

	@Test
	void extractImportLibraryWithOrdinalOnlyExports() throws IOException {
		AbiModel model = extract(fixture("ordinal-only-exports/ordinal.lib"));
		assertThat(model, is(sharedLibrary(hasItems(
			ordinalOnlyPeSymbol(1),
			ordinalOnlyPeSymbol(2)
		))));
		assertThat(model, is(sharedLibrary(not(hasItem(namedPeSymbol("func_one"))))));
	}

	private static Path fixture(String relativePath) {
		try {
			return Paths.get(ImportLibraryAbiExtractorIntegrationTests.class
				.getResource("/fixtures/" + relativePath).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found: " + relativePath
				+ " — build it per the BUILD file", e);
		}
	}
}
