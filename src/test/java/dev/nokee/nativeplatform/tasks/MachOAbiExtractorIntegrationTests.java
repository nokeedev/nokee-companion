package dev.nokee.nativeplatform.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static dev.nokee.commons.hamcrest.gradle.ThrowableMatchers.throwsException;
import static dev.nokee.nativeplatform.tasks.Fixtures.fixture;
import static dev.nokee.nativeplatform.tasks.MachOAbi.CPU_SUBTYPE_ARM64_ALL;
import static dev.nokee.nativeplatform.tasks.MachOAbi.CPU_SUBTYPE_X86_64_ALL;
import static dev.nokee.nativeplatform.tasks.MachOAbi.CPU_TYPE_ARM64;
import static dev.nokee.nativeplatform.tasks.MachOAbi.CPU_TYPE_X86_64;
import static dev.nokee.nativeplatform.tasks.MachOAbi.Export.strong;
import static dev.nokee.nativeplatform.tasks.MachOAbi.Export.weak;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for what {@link MachOBinaryHasher} reports about a Mach-O shared library.
 *
 * <p>Each test reads a prebuilt binary from src/test/resources/fixtures through {@link MachOAbi}, which
 * records the same visitor callbacks the hasher hashes. Mach-O spells a C symbol with a leading
 * underscore, so every name here carries one.
 */
class MachOAbiExtractorIntegrationTests {
	private static MachOAbi library(String arch) throws IOException {
		return MachOAbi.read(fixture("library-exports/macho/" + arch + "/libexports.dylib"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "arm64" })
	void reportsEveryExportedSymbolWithItsBinding(String arch) throws IOException {
		assertThat(library(arch).exports(), containsInAnyOrder(
			strong("_strong_func"),
			weak("_weak_func"),
			strong("_strong_var"),
			weak("_weak_var"),
			strong("_uses_internals")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "arm64" })
	void doesNotReportSymbolsTheLibraryKeepsToItself(String arch) throws IOException {
		// A hidden symbol becomes a private extern, which the link leaves out of the defined external
		// range the reader walks; a symbol with internal linkage never reaches it either.
		assertThat(names(library(arch)), not(hasItem("_hidden_func")));
		assertThat(names(library(arch)), not(hasItem("_internal_func")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "arm64" })
	void reportsInstallName(String arch) throws IOException {
		assertThat(library(arch).installNames(), contains("@rpath/libexports.dylib"));
	}

	@ParameterizedTest
	@CsvSource({ "x86_64, 16777223, 3", "arm64, 16777228, 0" })
	void reportsCpuTypeAndSubType(String arch, int cpuType, int cpuSubType) throws IOException {
		assertThat(library(arch).cpuTypes(), contains(cpuType));
		assertThat(library(arch).cpuSubTypes(), contains(cpuSubType));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "arm64" })
	void reportsNoExportedSymbolForLibraryThatExportsNothing(String arch) throws IOException {
		MachOAbi abi = MachOAbi.read(fixture("no-exports/macho/" + arch + "/libnoexports.dylib"));

		assertThat(abi.exports(), emptyIterable());
		assertThat(abi.installNames(), contains("@rpath/libnoexports.dylib")); // read, it just offers nothing
	}

	@Test
	void reportsEveryImageOfUniversalBinary() throws IOException {
		MachOAbi abi = MachOAbi.read(fixture("library-exports/macho/universal/libexports.dylib"));

		// A universal binary holds an image per architecture and the reader walks every one of them, so
		// each part is reported once per image — the exported symbols included.
		assertThat(abi.cpuTypes(), contains(CPU_TYPE_X86_64, CPU_TYPE_ARM64));
		assertThat(abi.cpuSubTypes(), contains(CPU_SUBTYPE_X86_64_ALL, CPU_SUBTYPE_ARM64_ALL));
		assertThat(abi.installNames(), contains("@rpath/libexports.dylib", "@rpath/libexports.dylib"));
		assertThat(abi.exports(), containsInAnyOrder(
			strong("_strong_func"), weak("_weak_func"), strong("_strong_var"), weak("_weak_var"), strong("_uses_internals"),
			strong("_strong_func"), weak("_weak_func"), strong("_strong_var"), weak("_weak_var"), strong("_uses_internals")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x86_64", "arm64" })
	void refusesStaticLibrary(String arch) {
		// An archive carries "!<arch>" where a Mach-O image carries its own magic. A static library is
		// snapshotted byte for byte rather than read for an ABI, so the reader never sees one.
		assertThat(() -> MachOAbi.read(fixture("static-archive/macho/" + arch + "/libstatic.a")),
			throwsException(instanceOf(IllegalArgumentException.class)));
	}

	private static List<String> names(MachOAbi abi) {
		return abi.exports().stream().map(MachOAbi.Export::name).collect(Collectors.toList());
	}
}
