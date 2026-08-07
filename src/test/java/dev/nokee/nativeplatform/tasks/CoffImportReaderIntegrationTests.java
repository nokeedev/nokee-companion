package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CoffImportReaderIntegrationTests {
	private static List<String> imports(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			List<String> result = new ArrayList<>();
			var coff = (CoffBlob.CoffObjectBlob) CoffBlob.parse(new BSource(channel));
			CoffBlob.CoffStringTable strtab = coff.strings();
			for (CoffBlob.CoffSymbol symbol : coff.symbols()) {
				if (symbol.sectionNumber() == 0 && symbol.storageClass() == 2 && symbol.value() == 0) {
					if (symbol.strx() == -1) {
						result.add(symbol.name());
					} else {
						result.add(strtab.get(symbol.strx()));
					}
				}
			}
			return result;
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalFunctionsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), hasItems("foo", "bar"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void extractsUndefinedExternalVariableAsImport(String arch) throws IOException {
		// A data import is an undefined external too; the reader does not filter by function-vs-data type.
		assertThat(imports(fixture(arch)), hasItem("gvar"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportDefinedExternalSymbolsAsImports(String arch) throws IOException {
		Collection<String> imports = imports(fixture(arch));
		assertThat(imports, not(hasItem("entry")));
		assertThat(imports, not(hasItem("local_helper")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReportInternalLinkageSymbolsAsImports(String arch) throws IOException {
		assertThat(imports(fixture(arch)), not(hasItem("secret")));
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
