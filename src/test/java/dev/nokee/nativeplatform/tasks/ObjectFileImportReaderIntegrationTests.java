package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link ObjectFileImportReader}, the magic-dispatching object reader used for
 * standalone object files and archive members. Confirms it routes each object format to the right reader
 * and yields the same imports (with format-specific decoration), both directly on a standalone object and
 * through an {@link ArchiveBinaryHasher} that walks a static library's members.
 *
 * Prebuilt objects live in src/test/resources/fixtures/object-imports/; see that directory's BUILD file.
 */
class ObjectFileImportReaderIntegrationTests {
	private static final ObjectFileImportReader reader = new ObjectFileImportReader();

	@ParameterizedTest
	@CsvSource({
		"elf/x86_64/imports.o,     foo, bar",
		"elf/aarch64/imports.o,    foo, bar",
		"macho/x86_64/imports.o,   _foo, _bar",
		"macho/arm64/imports.o,    _foo, _bar",
		"coff/x86_64/imports.obj,  foo, bar",
		"coff/arm64/imports.obj,   foo, bar",
	})
	void dispatchesByMagicAndExtractsImports(String relativePath, String firstImport, String secondImport) throws IOException {
		try (FileChannel channel = FileChannel.open(fixture(relativePath))) {
			AbiBinaryHasher.AbiBinaryHashCode model = reader.hash(channel, 0, channel.size());
			assertThat(model.type(), is(AbiBinaryHasher.Type.OBJECT_FILE));
			Set<Object> imports = ((AbiBinaryHasher.HasImportSymbols) model).getImportedSymbols();
			assertThat(imports, hasItems(firstImport.hashCode(), secondImport.hashCode()));
		}
	}

	@ParameterizedTest
	@CsvSource({
		"elf/x86_64/imports.a,     foo, bar",
		"elf/aarch64/imports.a,    foo, bar",
		"macho/x86_64/imports.a,   _foo, _bar",
		"macho/arm64/imports.a,    _foo, _bar",
		"coff/x86_64/imports.lib,  foo, bar",
		"coff/arm64/imports.lib,   foo, bar",
	})
	void extractsImportsFromEachMemberOfAStaticLibrary(String relativePath, String firstImport, String secondImport) throws IOException {
		AbiBinaryHasher archiveReader = new ArchiveBinaryHasher(reader);
		try (FileChannel channel = FileChannel.open(fixture(relativePath))) {
			AbiBinaryHasher.AbiBinaryHashCode model = archiveReader.hash(channel);
			assertThat(model.type(), is(AbiBinaryHasher.Type.STATIC_LIB));

			Set<AbiBinaryHasher.AbiBinaryHashCode> members = ((AbiBinaryHasher.HasMembers) model).getMembers();
			assertThat(members, contains(is(instanceOf(AbiBinaryHasher.HasImportSymbols.class))));

			AbiBinaryHasher.AbiBinaryHashCode member = members.iterator().next();
			assertThat(member.type(), is(AbiBinaryHasher.Type.OBJECT_FILE));
			Set<Object> imports = ((AbiBinaryHasher.HasImportSymbols) member).getImportedSymbols();
			assertThat(imports, hasItems(firstImport.hashCode(), secondImport.hashCode()));
		}
	}

	private static Path fixture(String relativePath) {
		try {
			return Paths.get(ObjectFileImportReaderIntegrationTests.class
				.getResource("/fixtures/object-imports/" + relativePath.trim()).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found: " + relativePath + " — build it per the BUILD file", e);
		}
	}
}
