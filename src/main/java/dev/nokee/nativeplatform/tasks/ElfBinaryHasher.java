package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.function.Consumer;

import static dev.nokee.nativeplatform.tasks.ElfBlob.*;

// Care was taken to avoid as many condition and allocation as possible
final class ElfBinaryHasher {
	private static final int SHT_SYMTAB = 2; // for sh_type
	private static final int SHT_DYNAMIC = 6; // for sh_type
	private static final int SHT_DYNSYM = 11; // for sh_type
	private static final int SHT_GNU_VERDEF = 0x6ffffffd; // for sh_type, the .gnu.version_d section
	private static final int SHT_GNU_VERSYM = 0x6fffffff; // for sh_type, the .gnu.version section
	private static final long DT_SONAME = 14;
	private static final long DT_NULL = 0;
	private static final int STB_GLOBAL = 1;
	private static final int STB_WEAK = 2;
	private static final int SHN_UNDEF = 0;

	private static final Set<Integer> dyn_types = new HashSet<>();
	private static final Set<Integer> rel_types = Collections.singleton(SHT_SYMTAB);
	static {
		dyn_types.add(SHT_DYNAMIC);
		dyn_types.add(SHT_DYNSYM);
		dyn_types.add(SHT_GNU_VERSYM);
		dyn_types.add(SHT_GNU_VERDEF);
	}

	private ElfBlob.ElfStringTable loadDynstr(ElfBlob.ElfSectionHeader ref) {
		int sh_link = ref.link();
		if (sh_link >= 0) { // if we overflow, there will be an exception
			return new ElfBlob.ElfStringTable(ref.owner().get(sh_link));
		}
		return null;
	}

	private Map<Integer, ElfBlob.ElfSectionHeader> hash(ElfBlob.ElfSectionTable sections, Set<Integer> types) {
		Map<Integer, ElfBlob.ElfSectionHeader> result = new HashMap<>();

		// Stops at the end of the table, not once every type is found: .gnu.version and .gnu.version_d are
		// absent from any library built without a version script, so a type that never turns up is the normal
		// case rather than an error.
		for (int i = 0; i < sections.size() && result.size() != types.size(); i++) {
			ElfBlob.ElfSectionHeader hdr = sections.get(i);
			int sh_type = hdr.type();

			if (types.contains(sh_type)) {
				result.put(sh_type, hdr);
			}
		}

		return result;
	}

	public void visitImports(ElfBlob blob, Consumer<? super String> visitor) {
		try (ElfBlob.ElfSectionTable sections = blob.sections()) {
			Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(sections, rel_types);

			ElfBlob.ElfSectionHeader symtab = shs.get(SHT_SYMTAB);

			ElfBlob.ElfStringTable strtab = loadDynstr(symtab);
			try (strtab) {
				if (symtab == null) {
					// no import table
				} else if (strtab != null) {
					visitGlobalOrWeakSymbols(symtab, sym -> {
						if (sym.shndx() == SHN_UNDEF) {
							String name = strtab.get(sym.name() & 0xFFFFFFFF);
							if (!name.isEmpty()) {
								visitor.accept(name);
							}
						}
					});
				} else {
					// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
					throw new RuntimeException("ELF shared library .dynsym is unreadable");
				}
			}
		}
	}

	public interface SonameAndExportVisitor {
		void visitArchitecture(int arch);
		void visitOsAbi(int osabi);
		void visitSoname(String soname);
		/**
		 * @param version  the version this symbol is bound to, or null when it carries none. Two libraries can
		 *                 hold the same name, binding, type and size at different versions: {@code .dynstr}
		 *                 keeps the bare name either way, and a consumer records the version it bound to in its
		 *                 own {@code .gnu.version_r}, so the version is part of what it linked against.
		 */
		void visitExport(String name, int info, long size, @Nullable String version, boolean defaultVersion);
		void visitAbiVersion(int abiversion);
		void visitType(int type);
	}

	public void visitSharedLib(ElfBlob blob, SonameAndExportVisitor visitor) throws IOException {
//		visitor.visitType(blob.header().e_type()); I don't think it actually matter
		visitor.visitArchitecture(blob.header().e_machine());
		visitor.visitOsAbi(blob.header().e_ident(EI_OSABI)); // TODO: Should we expose EI_OSABI?
		visitor.visitAbiVersion(blob.header().e_ident(EI_ABIVERSION)); // TODO: Should we expose EI_ABIVERSION?
		try (ElfBlob.ElfSectionTable sections = blob.sections()) {
			Map<Integer, ElfBlob.ElfSectionHeader> shs = hash(sections, dyn_types);

			ElfBlob.ElfSectionHeader dynamic = shs.get(SHT_DYNAMIC);
			ElfBlob.ElfSectionHeader dynsym = shs.get(SHT_DYNSYM);
			ElfBlob.ElfSectionHeader versym = shs.get(SHT_GNU_VERSYM);

			ElfBlob.ElfStringTable strtab = loadDynstr(dynsym);
			try (strtab) {
				if (dynamic != null && strtab != null) {
					Optional.ofNullable(extractSoname(blob, strtab, dynamic.offset(), dynamic.size())).ifPresent(visitor::visitSoname);
				}

				if (dynsym == null) {
					// no exprted symbols
				} else if (strtab != null) {
					// Walked once, ahead of the symbols, because the definitions are a chain rather than a table:
					// each one reaches the next through a byte offset, so there is no entry to index to. It is one
					// entry per version the library defines, not per symbol, and stays absent altogether for a
					// library built without a version script.
					String[] versions = loadVersionNames(shs.get(SHT_GNU_VERDEF), strtab);

					try (ElfBlob.ElfVersymTable versyms = versym == null ? null : new ElfBlob.ElfVersymTable(versym)) {
						visitGlobalOrWeakSymbols(dynsym, sym -> {
							if (sym.shndx() != SHN_UNDEF) {
								String name = strtab.get(sym.name() & 0xFFFFFFFF);
								if (!name.isEmpty()) {
									visitor.visitExport(name, sym.info(), sym.size(), versionOf(versyms, versions, sym.index()), isDefaultVersion(versyms, sym.index()));
								}
							}
						});
					}
				} else {
					// .dynsym is present but its string table/layout is unreadable: we cannot determine the exports.
					throw new RuntimeException("ELF shared library .dynsym is unreadable");
				}
			}
		}
	}

	@Nullable
	private String extractSoname(ElfBlob blob, ElfBlob.ElfStringTable strtab, long dynOff, long dynSize) throws IOException {
		int entSize = blob.dt_entsize();
		int count = (int) (dynSize / entSize);

		// Map the dynamic table: it is scanned entry by entry (until DT_NULL/DT_SONAME), so a mapping turns
		// those per-entry reads into memory accesses. Each entry i is at index i * entSize into this mapping.
		MappedByteBuffer dynamic = (MappedByteBuffer) blob.source.mmap(dynOff, dynSize).order(blob.order);
		try {
			for (int i = 0; i < count; i++) {
				int dyn = i * entSize;
				long tag = blob.d_tag(dynamic, dyn);
				long val = blob.d_val(dynamic, dyn);
				if (tag == DT_NULL) break;
				if (tag == DT_SONAME) {
					return strtab.get(val);
				}
			}
			return null;
		} finally {
			MappedBufferUtils.unmap(dynamic);
		}
	}


	// The version names a library defines, indexed by the version index .gnu.version carries for each symbol.
	// An array rather than a map because those indices are dense and start at 1, so a lookup is an array read
	// that boxes nothing: this is reached once per exported symbol, and a library like libstdc++ exports
	// thousands of them against a few dozen versions.
	private static final String[] NO_VERSIONS = new String[0];
	private static final int VERDEF_MIN_SIZE = 20; // a definition record, before the auxiliary record naming it

	private String[] loadVersionNames(@Nullable ElfBlob.ElfSectionHeader verdef, ElfBlob.ElfStringTable strtab) {
		if (verdef == null) {
			return NO_VERSIONS; // no version script: nothing defines a version, so nothing to resolve against
		}

		try (ElfBlob.ElfVerdefTable definitions = new ElfBlob.ElfVerdefTable(verdef)) {
			// Each record occupies at least VERDEF_MIN_SIZE bytes and the linker hands out indices from 1
			// upwards, so the section size bounds how high an index can run.
			String[] result = new String[(int) (verdef.size() / VERDEF_MIN_SIZE) + 2];
			definitions.forEach((index, nameOffset) -> {
				if (index >= 0 && index < result.length) {
					result[index] = strtab.get(nameOffset);
				}
			});
			return result;
		}
	}

	@Nullable
	private static String versionOf(@Nullable ElfBlob.ElfVersymTable versyms, String[] versions, int symbolIndex) {
		if (versyms == null) {
			return null;
		}

		// VER_NDX_LOCAL and VER_NDX_GLOBAL mean the symbol carries no version of its own, and the entry at
		// VER_NDX_GLOBAL names the library rather than a version, so neither is part of a symbol's identity.
		int index = versyms.get(symbolIndex) & ~VER_NDX_HIDDEN;
		if (index <= VER_NDX_GLOBAL || index >= versions.length) {
			return null;
		}

		return versions[index];
	}

	// Whether this is the definition an unversioned reference binds to. A library can define one name at
	// several versions at once, and only the definition without VER_NDX_HIDDEN is the one a consumer reaches
	// without naming a version itself. Moving the default from one to the other leaves every name, binding,
	// type and size untouched, and leaves the set of defined versions untouched too, while changing which
	// implementation a consumer links against.
	private static boolean isDefaultVersion(@Nullable ElfBlob.ElfVersymTable versyms, int symbolIndex) {
		return versyms == null || (versyms.get(symbolIndex) & VER_NDX_HIDDEN) == 0;
	}

	private void visitGlobalOrWeakSymbols(ElfBlob.ElfSectionHeader sh, Consumer<? super ElfBlob.ElfSymbol> visitor) {
		try (ElfBlob.ElfSymbolTable symtab = new ElfBlob.ElfSymbolTable(sh)) {
			for (ElfBlob.ElfSymbol sym : symtab) { // entry 0 is always STN_UNDEF
				int binding = sym.binding();
				if (binding == STB_GLOBAL || binding == STB_WEAK) {
					visitor.accept(sym);
				}
			}
		}
	}
}
