package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for {@link CoffBlob}: the symbols an object imports, and the symbols an import library
 * exports. Fixtures live in src/test/resources/fixtures/object-imports/; see that directory's BUILD file
 * for the commands to produce them. 64-bit COFF C symbols carry no leading underscore.
 */
class CoffBlobIntegrationTests {
	private static void blob(Path path, Consumer<? super CoffBlob> action) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			action.accept(CoffBlob.parse(new BSource(channel)));
		}
	}

	private static List<String> importLib(Path path) throws IOException {
		// TODO: Move this into utility class for listing all "export symbols" of a import library
		//   Care must be taken that the archive may contain a mixed object list... but we can probably safely ignore this at the moment.
		try (FileChannel channel = FileChannel.open(path)) {
			ByteBuffer sig = ByteBuffer.allocate(4);
			List<String> result = new ArrayList<>();
			try (ArchiveBlob.ArchiveMembers members = ArchiveBlob.parse(new BSource(channel)).members()) {
				for (ArchiveBlob.ArchiveMember member : members) {
					member.file().read(sig.clear());
					if (MicrosoftImportObjectBlob.isImportObjectMagic(sig.array())) {
						try (MicrosoftImportObjectBlob blob = MicrosoftImportObjectBlob.parse(member.file())) {
							result.add(blob.symbolName());
						}
					}
				}
			}
			return result;
		}
	}

	/** Reads every member of a library, each of which is a blob in its own right. */
	private static void members(Path path, Consumer<? super CoffBlob> action) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			try (ArchiveBlob.ArchiveMembers members = ArchiveBlob.parse(new BSource(channel)).members()) {
				var iter = members.iterator();
				assertThat(iter.hasNext(), is(true));
				while (iter.hasNext()) {
					action.accept(CoffBlob.parse(iter.next().file()));
				}
			}
		}
	}

	@Test
	void readsExportSymbols() throws IOException {
		assertThat(importLib(fixtureex("hello.lib")), containsInAnyOrder("_hello", "_bye"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void readsImportsOfObject(String arch) throws IOException {
		blob(object(arch), it -> {
			assertThat(it, instanceOf(CoffBlob.CoffObjectBlob.class));
			assertThat(imports(it), containsInAnyOrder("foo", "bar", "gvar"));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void doesNotReadDefinedSymbolsOfObjectAsImports(String arch) throws IOException {
		blob(object(arch), it -> {
			// A defined external is not an import, and neither is an internal-linkage symbol.
			assertThat(imports(it), not(hasItem("entry")));
			assertThat(imports(it), not(hasItem("local_helper")));
			assertThat(imports(it), not(hasItem("secret")));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void readsNameOfSymbolThatDoesNotFitInline(String arch) throws IOException {
		blob(object(arch), it -> {
			var object = (CoffBlob.CoffObjectBlob) it;
			var strings = object.strings();

			for (CoffBlob.CoffSymbol symbol : object.symbols()) {
				// A name of more than 8 bytes is not stored in the symbol, but at an offset in the string
				// table; a name that fits leaves no offset behind.
				if (symbol.strx() == -1) {
					assertThat(symbol.name().length(), is(not(0)));
				} else {
					assertThat(strings.get(symbol.strx()).length(), is(not(0)));
				}
			}

			assertThat(names(object), hasItem("local_helper")); // 12 bytes, so it lives in the string table
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void skipsAuxiliaryRecordsOfSymbolTable(String arch) throws IOException {
		blob(object(arch), it -> {
			var symbols = ((CoffBlob.CoffObjectBlob) it).symbols();

			// A section symbol carries an auxiliary record, which occupies an entry of the table without
			// being a symbol of its own — so the walk sees fewer symbols than the table has entries.
			int walked = 0;
			int entries = 0;
			for (CoffBlob.CoffSymbol symbol : symbols) {
				walked++;
				entries += 1 + symbol.numberOfAuxSymbols();
			}
			assertThat(entries, is((int) symbols.size()));
			assertThat(walked, is(not(entries)));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void readsImportsOfObjectMemberOfLibrary(String arch) throws IOException {
		// A member is a slice of the library, so nothing may be read relative to the enclosing channel.
		members(library(arch), it -> {
			assertThat(it, instanceOf(CoffBlob.CoffObjectBlob.class));
			assertThat(imports(it), containsInAnyOrder("foo", "bar", "gvar"));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "arm64", "x86_64" })
	void readsSameImportsFromObjectAndItsLibraryMember(String arch) throws IOException {
		List<String> fromObject = new ArrayList<>();
		blob(object(arch), it -> fromObject.addAll(imports(it)));

		members(library(arch), it -> assertThat(imports(it), contains(fromObject.toArray())));
	}

	/** The symbols the object imports: the external ones it leaves undefined, named through its string table. */
	private static List<String> imports(CoffBlob blob) {
		CoffBlob.CoffObjectBlob object = (CoffBlob.CoffObjectBlob) blob;
		CoffBlob.CoffStringTable strings = object.strings();
		List<String> result = new ArrayList<>();
		for (CoffBlob.CoffSymbol symbol : object.symbols()) {
			if (symbol.storageClass() != CoffBlob.IMAGE_SYM_CLASS_EXTERNAL) continue;
			if (symbol.sectionNumber() != CoffBlob.IMAGE_SYM_UNDEFINED) continue;
			if (symbol.value() != 0) continue; // a common symbol, which defines the symbol rather than imports it
			result.add(name(strings, symbol));
		}
		return result;
	}

	private static List<String> names(CoffBlob.CoffObjectBlob object) {
		CoffBlob.CoffStringTable strings = object.strings();
		List<String> result = new ArrayList<>();
		for (CoffBlob.CoffSymbol symbol : object.symbols()) {
			result.add(name(strings, symbol));
		}
		return result;
	}

	/** A name too long to sit inline is not stored in the symbol, but at an offset in the string table. */
	private static String name(CoffBlob.CoffStringTable strings, CoffBlob.CoffSymbol symbol) {
		return symbol.strx() == -1 ? symbol.name() : strings.get(symbol.strx());
	}

	private static Path object(String arch) {
		return fixture(arch, "imports.obj");
	}

	private static Path library(String arch) {
		return fixture(arch, "imports.lib");
	}

	private static Path fixture(String arch, String fileName) {
		try {
			return Paths.get(CoffBlobIntegrationTests.class
				.getResource("/fixtures/object-imports/coff/" + arch + "/" + fileName).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found for " + arch + " — build it per the BUILD file", e);
		}
	}

	private static Path fixtureex(String fileName) {
		try {
			return Paths.get(CoffBlobIntegrationTests.class
				.getResource("/fixtures/windows-import-lib/" + fileName).toURI());
		} catch (Exception e) {
			throw new RuntimeException("Fixture not found for " + "ddd" + " — build it per the BUILD file", e);
		}
	}

	private static List<String> symbols(CoffBlob blob) {
		assert blob instanceof CoffBlob.CoffObjectBlob;
		List<String> result = new ArrayList<>();
		for (CoffBlob.CoffSymbol symbol : ((CoffBlob.CoffObjectBlob) blob).symbols()) {
			result.add(symbol.name());
		}
		return result;
	}
}
