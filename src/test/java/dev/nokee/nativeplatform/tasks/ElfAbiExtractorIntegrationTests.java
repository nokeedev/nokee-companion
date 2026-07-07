package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
	static DefaultNativeLibraryAbiExtractor extractor;
	@TempDir static Path testDirectory;

	@BeforeAll
	static void setup() {
		Project project = ProjectBuilder.builder().withProjectDir(testDirectory.toFile()).build();
		extractor = new DefaultNativeLibraryAbiExtractor(project.getObjects());
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryWithNamedExports(String arch) throws IOException {
		AbiModel model = extractor.extract(fixture("named-exports/" + arch + "/libnamed.so"));
		assertThat(model, is(sharedLibrary(hasItems(
			strongElfSymbol("compute"),
			strongElfSymbol("greet"),
			strongElfSymbol("value")
		))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryWithNoExports(String arch) throws IOException {
		AbiModel model = extractor.extract(fixture("no-exports/" + arch + "/libno_exports.so"));
		assertThat(model, is(emptySharedLibrary()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractSharedLibraryDistinguishesWeakFromStrongSymbols(String arch) throws IOException {
		AbiModel model = extractor.extract(fixture("weak-symbols/" + arch + "/libweak.so"));
		assertThat(model, is(sharedLibrary(hasItems(
			strongElfSymbol("strong_func"),
			strongElfSymbol("strong_var"),
			weakElfSymbol("weak_func"),
			weakElfSymbol("weak_var")
		))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64"})
	void extractStaticArchiveReturnsStaticLibraryModel(String arch) throws IOException {
		AbiModel model = extractor.extract(fixture("static-archive/" + arch + "/libstatic.a"));
		assertThat(model, nullValue());
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
