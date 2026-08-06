package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ELF relocatable-object import extraction via {@link ElfBinaryHasher}.
 *
 * Prebuilt objects live in src/test/resources/fixtures/object-imports/. See that directory's BUILD file
 * for the commands to produce them. ELF C symbols carry no leading underscore.
 */
class ElfImportReaderIntegrationTests {
	private static final ElfBinaryHasher reader = new ElfBinaryHasher();

	private static Set<Object> imports(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			Set<Object> result = new LinkedHashSet<>();
			reader.visitImports(ElfBlob.parse(new BSource(channel)), result::add);
			return result;
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64" })
	void extractsUndefinedExternalFunctionsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), hasItems("foo", "bar"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64" })
	void extractsUndefinedExternalVariableAsImport(String arch) throws IOException {
		// A data import is an undefined external too; the reader does not filter by function-vs-data type.
		assertThat(imports(fixture(arch)), hasItem("gvar"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64" })
	void doesNotReportDefinedExternalSymbolsAsImports(String arch) throws IOException {
		Set<Object> imports = imports(fixture(arch));
		assertThat(imports, not(hasItem("entry")));
		assertThat(imports, not(hasItem("local_helper")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "aarch64", "x86_64" })
	void doesNotReportInternalLinkageSymbolsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), not(hasItem("secret")));
	}

	private static Path fixture(String arch) {
		try {
			return Paths.get(ElfImportReaderIntegrationTests.class
				.getResource("/fixtures/object-imports/elf/" + arch + "/imports.o").toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found for " + arch + " — build it per the BUILD file", e);
		}
	}
}
