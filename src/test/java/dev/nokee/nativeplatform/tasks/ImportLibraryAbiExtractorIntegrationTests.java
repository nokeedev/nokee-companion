package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
	static DefaultNativeLibraryAbiExtractor extractor;
	@TempDir static Path tempDir;

	@BeforeAll
	static void setup() {
		Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
		extractor = new DefaultNativeLibraryAbiExtractor(project.getObjects());
	}

	@Test
	void extractImportLibraryWithNamedExports() throws IOException {
		AbiEntry entry = extractor.extract(fixture("named-exports/named.lib"));
		assertThat(entry.model, is(sharedLibrary(hasItems(
			namedPeSymbol("compute"),
			namedPeSymbol("greet"),
			namedPeSymbol("value")
		))));
	}

	@Test
	void extractImportLibraryWithNoExports() throws IOException {
		AbiEntry entry = extractor.extract(fixture("no-exports/no_exports.lib"));
		assertThat(entry.model, is(emptySharedLibrary()));
	}

	@Test
	void extractImportLibraryWithOrdinalOnlyExports() throws IOException {
		AbiEntry entry = extractor.extract(fixture("ordinal-only-exports/ordinal.lib"));
		assertThat(entry.model, is(sharedLibrary(hasItems(
			ordinalOnlyPeSymbol(1),
			ordinalOnlyPeSymbol(2)
		))));
		assertThat(entry.model, is(sharedLibrary(not(hasItem(namedPeSymbol("func_one"))))));
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
