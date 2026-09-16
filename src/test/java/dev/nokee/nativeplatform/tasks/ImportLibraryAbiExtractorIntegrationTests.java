package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.nokee.nativeplatform.tasks.AbiMatchers.codeExport;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.dataExport;
import static dev.nokee.nativeplatform.tasks.Fixtures.fixture;
import static dev.nokee.nativeplatform.tasks.ImportLibraryAbi.IMAGE_FILE_MACHINE_AMD64;
import static dev.nokee.nativeplatform.tasks.ImportLibraryAbi.IMAGE_FILE_MACHINE_I386;
import static dev.nokee.nativeplatform.tasks.MicrosoftImportObjectBlob.IMPORT_OBJECT_NAME;
import static dev.nokee.nativeplatform.tasks.MicrosoftImportObjectBlob.IMPORT_OBJECT_NAME_NOPREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for what a Windows import library reports about the exports it stands for.
 *
 * <p>Each test reads a prebuilt library from src/test/resources/fixtures through {@link ImportLibraryAbi},
 * which walks the archive's members the way {@code LinkAbiAware} does. Both targets are read for every
 * fixture, because the decoration a target puts on a C symbol is part of what the reader reports.
 */
class ImportLibraryAbiExtractorIntegrationTests {
	/**
	 * A target the one exports.def is built for.
	 *
	 * @param prefix    the decoration the target puts on a cdecl symbol, which the exported name lacks
	 * @param nameType  how a named export relates its symbol to the name the DLL exports
	 */
	record Target(String directory, int machine, int nameType, String prefix) {
		@Override
		public String toString() {
			return directory;
		}
	}

	static Stream<Target> targets() {
		return Stream.of(
			new Target("x86", IMAGE_FILE_MACHINE_I386, IMPORT_OBJECT_NAME_NOPREFIX, "_"),
			new Target("x86_64", IMAGE_FILE_MACHINE_AMD64, IMPORT_OBJECT_NAME, ""));
	}

	private static ImportLibraryAbi library(Target target) throws IOException {
		return ImportLibraryAbi.read(fixture("import-library/" + target.directory() + "/exports.lib"));
	}

	@ParameterizedTest
	@MethodSource("targets")
	void reportsEveryExportWithHowTheDllNamesIt(Target target) throws IOException {
		String prefix = target.prefix();

		assertThat(library(target).exports(), containsInAnyOrder(
			codeExport(prefix + "add").named(target.nameType()),
			dataExport(prefix + "counter").named(target.nameType()),
			// A NONAME export leaves no name in the DLL's export table, so a consumer reaches it at the
			// ordinal instead — while the symbol it references still carries the name written here.
			codeExport(prefix + "hidden_one").atOrdinal(1),
			codeExport(prefix + "hidden_two").atOrdinal(2)));
	}

	@ParameterizedTest
	@MethodSource("targets")
	void reportsDllEachExportComesFrom(Target target) throws IOException {
		assertThat(each(library(target), ImportLibraryAbi.Export::dllName), everyItem(is("exports.dll")));
	}

	@ParameterizedTest
	@MethodSource("targets")
	void reportsArchitectureEachExportTargets(Target target) throws IOException {
		assertThat(each(library(target), ImportLibraryAbi.Export::machine), everyItem(is(target.machine())));
	}

	@ParameterizedTest
	@MethodSource("targets")
	void reportsVersionOfImportObjectHeader(Target target) throws IOException {
		assertThat(each(library(target), ImportLibraryAbi.Export::version), everyItem(is(0)));
	}

	@ParameterizedTest
	@MethodSource("targets")
	void doesNotReportTheLibrarysOwnObjects(Target target) throws IOException {
		// Next to the short import objects a library holds three ordinary COFF objects — the import
		// descriptor, its terminator and the thunk data — and none of them stands for an export.
		assertThat(library(target).exports(), hasSize(4));
		assertThat(each(library(target), ImportLibraryAbi.Export::symbolName),
			not(hasItem("__IMPORT_DESCRIPTOR_exports")));
	}

	private static <T> List<T> each(ImportLibraryAbi abi, Function<ImportLibraryAbi.Export, T> field) {
		return abi.exports().stream().map(field).collect(Collectors.toList());
	}
}
