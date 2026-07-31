package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for COFF object import extraction via {@link CoffImportReader}.
 *
 * Prebuilt objects live in src/test/resources/fixtures/object-imports/. See that directory's BUILD file
 * for the commands to produce them. 64-bit COFF C symbols carry no leading underscore.
 */
class CoffImportReaderIntegrationTests {
	private static final CoffImportReader reader = new CoffImportReader();

	private static Set<Object> imports(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			AbiBinaryHasher.AbiBinaryHashCode model = reader.hash(channel);
			assertThat(model.type(), is(AbiBinaryHasher.Type.OBJECT_FILE));
			return ((AbiBinaryHasher.HasImportSymbols) model).getImportedSymbols();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalFunctionsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), hasItems("foo".hashCode(), "bar".hashCode()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalVariableAsImport(String arch) throws IOException {
		// A data import is an undefined external too; the reader does not filter by function-vs-data type.
		assertThat(imports(fixture(arch)), hasItem("gvar".hashCode()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportDefinedExternalSymbolsAsImports(String arch) throws IOException {
		Set<Object> imports = imports(fixture(arch));
		assertThat(imports, not(hasItem("entry".hashCode())));
		assertThat(imports, not(hasItem("local_helper".hashCode())));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportInternalLinkageSymbolsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), not(hasItem("secret".hashCode())));
	}

	private static Path fixture(String arch) {
		try {
			return Paths.get(CoffImportReaderIntegrationTests.class
				.getResource("/fixtures/object-imports/coff/" + arch + "/imports.obj").toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found for " + arch + " — build it per the BUILD file", e);
		}
	}
}
