package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.throwsException;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.strongFunction;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.strongVariable;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.threadLocalVariable;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.versionDefinition;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.weakFunction;
import static dev.nokee.nativeplatform.tasks.AbiMatchers.weakVariable;
import static dev.nokee.nativeplatform.tasks.Fixtures.fixture;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for what {@link ElfBinaryHasher} reports about an ELF shared library.
 *
 * <p>Each test reads a prebuilt binary from src/test/resources/fixtures through {@link ElfAbi}, which
 * records the same visitor callbacks the hasher hashes. Both architectures are read for every fixture,
 * because the reader resolves every offset and size from the image rather than assuming a layout.
 */
class ElfAbiExtractorIntegrationTests {
	private static ElfAbi library(String arch) throws IOException {
		return ElfAbi.read(fixture("library-exports/elf/" + arch + "/libexports.so"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsEveryExportedSymbolWithItsBindingAndType(String arch) throws IOException {
		assertThat(library(arch).exports(), containsInAnyOrder(
			strongFunction("strong_func"),
			weakFunction("weak_func"),
			strongVariable("strong_var").ofSize(4),
			weakVariable("weak_var").ofSize(4),
			strongFunction("uses_internals")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void doesNotReportSymbolsTheLibraryKeepsToItself(String arch) throws IOException {
		// One is hidden and the other has internal linkage, so neither reaches the dynamic symbol table.
		assertThat(names(library(arch)), not(hasItem("hidden_func")));
		assertThat(names(library(arch)), not(hasItem("internal_func")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void doesNotReportSymbolsTheLibraryImports(String arch) throws IOException {
		// The library links against libc, so its dynamic symbol table also holds what it leaves
		// undefined. Those are what the library asks of others rather than what it offers.
		assertThat(names(library(arch)), not(hasItem("__cxa_finalize")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsNoVersionWhenLibraryIsBuiltWithoutVersionScript(String arch) throws IOException {
		// .gnu.version and .gnu.version_d are both absent from such a library, so nothing binds a symbol
		// to a version and every symbol is the definition an unversioned reference reaches.
		assertThat(each(library(arch), ElfAbi.Export::version), everyItem(nullValue()));
		assertThat(each(library(arch), ElfAbi.Export::defaultVersion), everyItem(is(true)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsSoname(String arch) throws IOException {
		assertThat(library(arch).sonames(), contains("libexports.so"));
	}

	@ParameterizedTest
	@CsvSource({ "x86_64, 62", "aarch64, 183" })
	void reportsArchitecture(String arch, int machine) throws IOException {
		assertThat(library(arch).architecture(), is(machine));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsOsAbiAndItsVersion(String arch) throws IOException {
		assertThat(library(arch).osAbi(), is(ElfAbi.ELFOSABI_NONE));
		assertThat(library(arch).abiVersion(), is(0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsNoExportedSymbolForLibraryThatExportsNothing(String arch) throws IOException {
		ElfAbi abi = ElfAbi.read(fixture("no-exports/elf/" + arch + "/libnoexports.so"));

		assertThat(abi.exports(), emptyIterable());
		assertThat(abi.sonames(), contains("libnoexports.so")); // the library is read, it just offers nothing
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsVersionEachExportedSymbolIsDefinedAt(String arch) throws IOException {
		ElfAbi abi = ElfAbi.read(fixture("versioned-exports/elf/" + arch + "/libversioned.so"));

		assertThat(abi.exports(), containsInAnyOrder(
			// One name defined at two versions: an unversioned reference binds to the LIB_2.0
			// definition, and only a reference naming LIB_1.0 reaches the other.
			strongFunction("compat").atDefaultVersion("LIB_2.0"),
			strongFunction("compat").atNonDefaultVersion("LIB_1.0"),
			strongFunction("stable").atDefaultVersion("LIB_2.0"),
			versionDefinition("LIB_1.0").atDefaultVersion("LIB_1.0"),
			versionDefinition("LIB_2.0").atDefaultVersion("LIB_2.0")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void reportsThreadLocalSymbolApartFromOrdinaryOne(String arch) throws IOException {
		ElfAbi abi = ElfAbi.read(fixture("thread-local-exports/elf/" + arch + "/libthreadlocal.so"));

		assertThat(abi.exports(), containsInAnyOrder(
			threadLocalVariable("tls_var").ofSize(4),
			strongVariable("plain_var").ofSize(4),
			strongFunction("read_both")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "aarch64" })
	void refusesStaticLibrary(String arch) {
		// An archive carries "!<arch>" where an ELF image carries its own magic. A static library is
		// snapshotted byte for byte rather than read for an ABI, so the reader never sees one.
		assertThat(() -> ElfAbi.read(fixture("static-archive/elf/" + arch + "/libstatic.a")),
			throwsException(instanceOf(IllegalArgumentException.class)));
	}

	private static List<String> names(ElfAbi abi) {
		return each(abi, ElfAbi.Export::name);
	}

	private static <T> List<T> each(ElfAbi abi, Function<ElfAbi.Export, T> field) {
		return abi.exports().stream().map(field).collect(Collectors.toList());
	}
}
