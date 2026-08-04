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
 * Integration tests for Mach-O object import extraction, the import side of {@link MachOBinaryHasher}.
 *
 * Prebuilt objects live in src/test/resources/fixtures/object-imports/. See that directory's BUILD file
 * for the commands to produce them. Mach-O C symbols carry a leading underscore.
 */
class MachOImportReaderIntegrationTests {
	private static final MachOBinaryHasher reader = new MachOBinaryHasher();

	private static Set<Object> imports(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			AbiBinaryHasher.AbiBinaryHashCode model = reader.hash(channel, 0, channel.size());
			assertThat(model.type(), is(AbiBinaryHasher.Type.OBJECT_FILE));
			return ((AbiBinaryHasher.HasImportSymbols) model).getImportedSymbols();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalFunctionsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), hasItems("_foo".hashCode(), "_bar".hashCode()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalVariableAsImport(String arch) throws IOException {
		// A data import is an undefined external too; the reader does not filter by function-vs-data type.
		assertThat(imports(fixture(arch)), hasItem("_gvar".hashCode()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportDefinedExternalSymbolsAsImports(String arch) throws IOException {
		Set<Object> imports = imports(fixture(arch));
		assertThat(imports, not(hasItem("_entry".hashCode())));
		assertThat(imports, not(hasItem("_local_helper").hashCode()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportInternalLinkageSymbolsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), not(hasItem("_secret".hashCode())));
	}

	private static Path fixture(String arch) {
		try {
			return Paths.get(MachOImportReaderIntegrationTests.class.getResource("/fixtures/object-imports/macho/" + arch + "/imports.o").toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found for " + arch + " — build it per the BUILD file", e);
		}
	}
}
