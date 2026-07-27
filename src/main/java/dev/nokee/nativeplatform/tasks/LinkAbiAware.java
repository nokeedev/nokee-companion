package dev.nokee.nativeplatform.tasks;

import dev.nokee.commons.gradle.provider.ZipProvider;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Optional;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Callable;

import static dev.nokee.nativeplatform.tasks.ArchiveBlob.skipSymbolTables;
import static dev.nokee.nativeplatform.tasks.ElfBlob.ET_DYN;
import static dev.nokee.nativeplatform.tasks.ElfBlob.ET_REL;
import static dev.nokee.nativeplatform.tasks.ElfBlob.STT_NOTYPE;
import static dev.nokee.nativeplatform.tasks.ElfBlob.STT_OBJECT;
import static dev.nokee.nativeplatform.tasks.ElfBlob.STT_TLS;
import static dev.nokee.nativeplatform.tasks.MachOBlob.*;

// This class is considered private for the moment
public interface LinkAbiAware extends Task {
	@Internal
	Property<LinkAbiExtension> getExt_linkAbi();

	@Nested
	default LinkAbiExtension getLinkAbi() {
		if (!getExt_linkAbi().isPresent()) { // safe as we control the lifecycle
			ObjectFactory objects = getProject().getObjects();
			LinkAbiExtension extension = objects.newInstance(LinkAbiExtension.class);
			getExt_linkAbi().set(extension);
		}

		return getExt_linkAbi().get();
	}

	enum AbiSnapshotter {
		NONE,
		FULL_ABI,
		NARROW_ABI
	}

	abstract /*final*/ class LinkAbiExtension {
		private SetProperty<Object> unresolved;
		private SetProperty<Object> hashes;

		private static final ElfBinaryHasher elf = new ElfBinaryHasher();
		private static final MachOBinaryHasher macho = new MachOBinaryHasher();
		private static final CoffBinaryHasher coff = new CoffBinaryHasher();

		private static abstract class InFiles {
			private final Set<FileSystemLocation> elements;

			protected InFiles(Set<FileSystemLocation> elements) {
				this.elements = elements;
			}

			public final void accept(Step1Visitor visitor) {
				ByteBuffer hdr = ByteBuffer.allocate(8);
				for (FileSystemLocation element : elements) {
					Path path = element.getAsFile().toPath();
					try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
						BSource source = new BSource(channel);
						source.read(hdr.clear());
						if (ElfBlob.isElfMagic(hdr.array())) {
							visitElf(path, ElfBlob.parse(source), visitor);
						} else if (MachOBlob.isMachOMagic(hdr.array())) {
							visitMachO(path, MachOBlob.parse(source), visitor);
						} else if (ArchiveBlob.isArMagic(hdr.array())) {
							visitArchive(path, ArchiveBlob.parse(source), visitor);
						} else if (CoffBlob.isCoffMagic(hdr.array())) {
							visitCoffObject(path, (CoffBlob.CoffObjectBlob) CoffBlob.parse(source), visitor);
						} else {
							throw new UnsupportedOperationException("Invalid file with signature '" + HexFormat.of().formatHex(hdr.array()) + "' on file '" + path + "'");
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}

			protected abstract void visitCoffObject(Path path, CoffBlob.CoffObjectBlob blob, Step1Visitor visitor);

			protected abstract void visitElf(Path path, ElfBlob blob, Step1Visitor visitor);
			protected void visitMachO(Path path, MachOBlob blob, Step1Visitor visitor) {
				if (blob instanceof MachOBlob.MachOUniversalBlob) {
					try (MachOArchitectureTable archs = ((MachOUniversalBlob) blob).architectures()) {
						for (MachOBlob.MachOImageBlob architecture : archs) {
							visitMachO(path, architecture, visitor);
						}
					}
				} else if (blob instanceof MachOBlob.MachOImageBlob) {
					visitMachO(path, (MachOBlob.MachOImageBlob) blob, visitor);
				} else {
					throw new RuntimeException("invalid mach-o blob on file '" + path + "'");
				}
			}
			protected abstract void visitMachO(Path path, MachOBlob.MachOImageBlob blob, Step1Visitor visitor);
			protected abstract void visitArchive(Path path, ArchiveBlob blob, Step1Visitor visitor);
		}

		enum SharedLibFormat {
			ELF {
				@Override
				public HashCode hash(Path path, ImportSymbols imports) {
					PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
					try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
						elf.visitSharedLib(ElfBlob.parse(new BSource(channel)), new ElfBinaryHasher.SonameAndExportVisitor() {
							@Override
							public void visitArchitecture(int arch) {
								System.out.println("ARCH " + arch);
								hasher.putInt(arch);
							}

							@Override
							public void visitOsAbi(int osabi) {
								System.out.println("OS ABI " + osabi);
								hasher.putInt(osabi);
							}

							@Override
							public void visitSoname(String soname) {
								System.out.println("SONAME " + soname);
								hasher.putString(soname);
							}

							@Override
							public void visitExport(String name, int info, long size, String version, boolean defaultVersion) {
								if (imports.contains(name)) {
									System.out.println("export symbol '" + name + "' " + info + " -- " + size + " @ " + version + (defaultVersion ? " (default)" : ""));
									hasher.putString(name);

									// A versioned symbol binds its consumer to that version: the linker writes the
									// version into the consumer's own .gnu.version_r, and the loader refuses a library
									// that no longer defines it. .dynstr holds the bare name either way, so the version
									// has to be snapshot next to the name rather than read out of it.
									hasher.putString(version == null ? "" : version);

									// Which of a name's versions is the default decides what an unversioned reference
									// binds to, so two libraries defining the same name at the same versions still
									// hand a consumer different implementations when the default moves between them.
									hasher.putBoolean(defaultVersion);

									// st_info packs the binding in the high nibble and the type in the low one.
									// The binding is snapshot whole: weak and strong resolve differently.
									hasher.putInt(info >> 4);
									hasher.putInt(abiKind(info));

									// A size reaches a link result only through a copy relocation, where the linker
									// reserves st_size bytes in the consumer's own .bss and has the loader copy the
									// library's storage across at startup. A library that later grows the object
									// leaves that reservation too small, which the loader reports as the symbol
									// having a different size in the shared object. It applies to any object, not
									// just arrays: int to long long is caught the same way. Functions are left out
									// because st_size on a function measures its body, which nothing in a consumer's
									// link result depends on.
									//
									// TODO: Narrow this to the symbols that can actually take a copy relocation. It
									//  is decided by whether the referencing code was compiled -fPIC, which reaches
									//  an object through the GOT and needs nothing fixed at link time, and not by
									//  whether the output is a non-PIC executable: -fPIE takes copy relocations too,
									//  and it is the default on most distributions. That is not readable from the
									//  symbol - an undefined symbol is NOTYPE GLOBAL UND whichever way it was
									//  compiled - and only shows up in the relocations against it in the consumer's
									//  own object files, GOT-based against direct. Those objects are read only under
									//  NARROW_ABI, so the sharper check belongs there. Until then a name moving
									//  between a function and a variable relinks, which is more than necessary.
									if ((info & 0xF) == STT_OBJECT) {
										hasher.putLong(size);
									}
								}
							}

							@Override
							public void visitAbiVersion(int abiversion) {
								System.out.println("abiversion " + abiversion);
								hasher.putInt(abiversion);
							}

							@Override
							public void visitType(int type) {
								System.out.println("type " + type);
								hasher.putInt(type);
							}
						});
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					return hasher.hash();
				}
			}, MACHO {
				@Override
				public HashCode hash(Path path, ImportSymbols imports) {
					PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
					try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
						macho.visitSharedLib(MachOBlob.parse(new BSource(channel)), new MachOBinaryHasher.ExportOrInstallNameVisitor() {
							@Override
							public void visitInstallName(String installName) {
								System.out.println("Install name " + installName);
								hasher.putString(installName);
							}

							@Override
							public void visitExportSymbol(String name, boolean weakBinding) {
								if (imports.contains(name)) {
									System.out.println("SYMBOLE '" + name + " ' " + weakBinding);
									hasher.putString(name);
									hasher.putBoolean(weakBinding);
								}
							}

							@Override
							public void visitCpuType(int cputype) {
								System.out.println("CPUTYPE " + cputype);
								hasher.putInt(cputype);
							}

							@Override
							public void visitCpuSubType(int cpusubtype) {
								System.out.println("CPU SUBTYPE " + cpusubtype);
								hasher.putInt(cpusubtype);
							}
						});
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					return hasher.hash();
				}
			}, IMP {
				@Override
				public HashCode hash(Path path, ImportSymbols imports) {
					PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
					try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
						ArchiveBlob ar = ArchiveBlob.parse(new BSource(channel));
						try (ArchiveBlob.ArchiveMembers members = ar.members()) {
							members.forEach(MicrosoftImportObjectBlob.onlyImportObjects(it -> {
								String name = it.symbolName();
								if (imports.contains(name)) {
									System.out.println("WAT? '" + name + "' " + it.ordinalOrHint() + " -- " + it.type() + " -- " + it.version());
									hasher.putString(name);
									hasher.putInt(it.machine());
									hasher.putInt(it.ordinalOrHint());
									hasher.putInt(it.type());
									hasher.putInt(it.version());
								}
							}));
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					return hasher.hash();
				}
			};

			// The type an exported symbol carries in the low nibble of st_info, reduced to the one distinction
			// a consumer's link result depends on.
			//
			// STT_TLS is that distinction. A thread-local symbol is reached through its own relocations, and
			// ld refuses to mix the two: a TLS definition against a non-TLS reference, or the reverse, is a
			// hard error rather than a silently wrong link.
			//
			// Every other type collapses. The linker resolves an undefined symbol by name and never checks
			// the type, so calling a data symbol, or reading a function as data, links clean either way and
			// faults at run time instead — and relinking against the changed library produces the very same
			// binary, so STT_FUNC and STT_OBJECT buy nothing by staying apart. STT_GNU_IFUNC collapses too:
			// the consumer emits an ordinary jump slot against the name, and the loader is what calls the
			// resolver, so a symbol moving between STT_FUNC and STT_GNU_IFUNC leaves the consumer's
			// relocations, and its bytes, unchanged.
			private static int abiKind(int info) {
				int type = info & 0xF;
				return type == STT_TLS ? type : STT_NOTYPE;
			}

			public abstract HashCode hash(Path path, ImportSymbols imports);
		}

		private interface Step1Visitor {
			void visitImport(String name);
			void visitAdditionalObjectFile(Path path);
			void visitSharedLibrary(Path path, SharedLibFormat format);
			void visitStaticLibrary(Path path);
			void visitImportLibrary(Path path);
		}


		// Source files (aka object files) are snapshotted as input files
		//   We only need to warn strange usecases (like adding static or shared lib to the sources)
		private static final class SourceFiles extends InFiles {
			private SourceFiles(Set<FileSystemLocation> elements) {
				super(elements);
			}

			@Override
			protected void visitCoffObject(Path path, CoffBlob.CoffObjectBlob blob, Step1Visitor visitor) {
				System.out.println("Visiting '" + path + "'");
				coff.visitImports(blob, visitor::visitImport);
			}

			@Override
			protected void visitElf(Path path, ElfBlob blob, Step1Visitor visitor) {
				switch (blob.e_type()) {
					case ET_REL:
						elf.visitImports(blob, visitor::visitImport);
						break;
					case ET_DYN:
						// ignore, will be snapshot byte-for-byte, users should not put shared library here
						break;
					default: throw new UnsupportedOperationException("invalid elf type '" + blob.e_type() + "' on file '" + path + "'");
				}
			}

			@Override
			protected void visitMachO(Path path, MachOImageBlob blob, Step1Visitor visitor) {
				switch (blob.filetype()) {
					case MH_OBJECT:
						macho.visitImports(blob, visitor::visitImport);
						break;
					case MH_DYLIB:
					case MH_DYLIB_STUB:
						// ignore, will be snapshot byte-for-byte, users should not put shared library here
						break;
					default: throw new UnsupportedOperationException("invalid mach-o type '" + blob.filetype() + "' on file '" + path + "'");
				}
			}

			@Override
			protected void visitArchive(Path path, ArchiveBlob blob, Step1Visitor visitor) {
				// ignore, library will be snapshot byte-for-byte, users should not put static library here

				ByteBuffer hdr = ByteBuffer.allocate(8);
				try (ArchiveBlob.ArchiveMembers members = blob.members()) {
					members.forEach(skipSymbolTables(member -> {
						BSource source = member.file();
						source.read(hdr.clear());
						if (ElfBlob.isElfMagic(hdr.array())) {
							ElfBlob elf = ElfBlob.parse(source);
							assert elf.e_type() == ET_REL;
							LinkAbiExtension.elf.visitImports(elf, visitor::visitImport);
						} else if (MachOBlob.isMachOMagic(hdr.array())) {
							MachOBlob macho = MachOBlob.parse(source);
							assert macho instanceof MachOBlob.MachOImageBlob && ((MachOImageBlob) macho).filetype() == MH_OBJECT;
							LinkAbiExtension.macho.visitImports(macho, visitor::visitImport);
						} else if (MicrosoftImportObjectBlob.isImportObjectMagic(hdr.array())) {
							// skip import objects
						} else if (CoffBlob.isCoffMagic(hdr.array())) {
							CoffBlob coff = CoffBlob.parse(source);
							assert coff instanceof CoffBlob.CoffObjectBlob;
							LinkAbiExtension.coff.visitImports((CoffBlob.CoffObjectBlob) coff, visitor::visitImport);
						} else {
							throw new RuntimeException("unknown member '" + member.identifier() + "' from '" + path + "'");
						}
					}));
				}
			}
		}

		// However, libs files need to be processed to correctly snapshot what is needed
		private static final class LibraryFiles extends InFiles {
			public LibraryFiles(Set<FileSystemLocation> elements) {
				super(elements);
			}

			@Override
			protected void visitCoffObject(Path path, CoffBlob.CoffObjectBlob blob, Step1Visitor visitor) {
				// TODO: Should we have object files here??? We should just have ar file instead?
				throw new UnsupportedOperationException("Why is there an object file here '" + path + "'");
			}

			@Override
			protected void visitElf(Path path, ElfBlob blob, Step1Visitor visitor) {
				switch (blob.e_type()) {
					case ET_REL:
						visitor.visitAdditionalObjectFile(path);
						elf.visitImports(blob, visitor::visitImport);
						break;
					case ET_DYN:
						visitor.visitSharedLibrary(path, SharedLibFormat.ELF);
						break;
					default: throw new UnsupportedOperationException("invalid elf type '" + blob.e_type() + "' on file '" + path + "'");
				}
			}

			@Override
			protected void visitMachO(Path path, MachOImageBlob blob, Step1Visitor visitor) {
				switch (blob.filetype()) {
					case MH_OBJECT:
						visitor.visitAdditionalObjectFile(path);
						macho.visitImports(blob, visitor::visitImport);
						break;
					case MH_DYLIB:
					case MH_DYLIB_STUB:
						visitor.visitSharedLibrary(path, SharedLibFormat.MACHO);
						break;
					default: throw new UnsupportedOperationException("invalid mach-o type '" + blob.filetype() + "' on file '" + path + "'");
				}
			}

			@Override
			protected void visitArchive(Path path, ArchiveBlob blob, Step1Visitor visitor) {
				MutableBoolean isImportLib = new MutableBoolean(false);
				ByteBuffer hdr = ByteBuffer.allocate(8);
				try (ArchiveBlob.ArchiveMembers members = blob.members()) {
					members.forEach(skipSymbolTables(member -> {
						BSource source = member.file();
						source.read(hdr.clear());
						if (ElfBlob.isElfMagic(hdr.array())) {
							ElfBlob elf = ElfBlob.parse(source);
							assert elf.e_type() == ET_REL;
							LinkAbiExtension.elf.visitImports(elf, visitor::visitImport);
						} else if (MachOBlob.isMachOMagic(hdr.array())) {
							MachOBlob macho = MachOBlob.parse(source);
							assert macho instanceof MachOBlob.MachOImageBlob && ((MachOImageBlob) macho).filetype() == MH_OBJECT;
							LinkAbiExtension.macho.visitImports(macho, visitor::visitImport);
						} else if (MicrosoftImportObjectBlob.isImportObjectMagic(hdr.array())) {
							isImportLib.setValue(true); // mark this static lib as import lib
						} else if (CoffBlob.isCoffMagic(hdr.array())) {
							CoffBlob coff = CoffBlob.parse(source);
							assert coff instanceof CoffBlob.CoffObjectBlob;
							LinkAbiExtension.coff.visitImports((CoffBlob.CoffObjectBlob) coff, visitor::visitImport);
						} else {
							throw new RuntimeException("unknown member '" + member.identifier() + "' from '" + path + "'");
						}
					}));
				}

				if (isImportLib.asBoolean()) {
					visitor.visitImportLibrary(path);
				} else {
					visitor.visitStaticLibrary(path); // Should visit after so we can figure out if it's an import lib
				}
			}

			private static class MutableBoolean {
				private boolean b;

				public MutableBoolean(boolean b) {
					this.b = b;
				}

				public boolean asBoolean() {
					return b;
				}

				public void setValue(boolean b) {
					this.b = b;
				}
			}
		}

		// However, libs files for full link ABI are processed differently
//		private static final class FullLibraryFiles extends InFiles {
//			public FullLibraryFiles(Set<FileSystemLocation> elements) {
//				super(elements);
//			}
//
//			@Override
//			protected void visitCoffObject(Path path, CoffBlob.CoffObjectBlob blob, Step1Visitor visitor) {
//
//			}
//
//			@Override
//			protected void visitElf(Path path, ElfBlob blob, Step1Visitor visitor) {
//				switch (blob.e_type()) {
//					case ET_REL:
//						// ignores
//						break;
//					case ET_DYN:
//						visitor.visitSharedLibrary(path, SharedLibFormat.ELF);
//						break;
//					default: throw new UnsupportedOperationException("invalid elf type '" + blob.e_type() + "' on file '" + path + "'");
//				}
//			}
//
//			@Override
//			protected void visitMachO(Path path, MachOImageBlob blob, Step1Visitor visitor) {
//				switch (blob.filetype()) {
//					case MH_OBJECT:
//						// ignores
//						break;
//					case MH_DYLIB:
//					case MH_DYLIB_STUB:
//						visitor.visitSharedLibrary(path, SharedLibFormat.MACHO);
//						break;
//					default: throw new UnsupportedOperationException("invalid mach-o type '" + blob.filetype() + "' on file '" + path + "'");
//				}
//			}
//
//			@Override
//			protected void visitArchive(Path path, ArchiveBlob blob, Step1Visitor visitor) {
//				ByteBuffer hdr = ByteBuffer.allocate(8);
//				boolean isImportLib = false;
//				for (ArchiveBlob.ArchiveMember member : blob.members()) {
//					BSource source = member.file();
//					source.read(hdr.clear());
//					if (MicrosoftImportObjectBlob.isImportObjectMagic(hdr.array())) {
//						isImportLib = true;
//						break;
//					}
//				}
//
//				if (isImportLib) {
//					visitor.visitImportLibrary(path);
//				} else {
//					visitor.visitStaticLibrary(path);
//				}
//			}
//		}

		private static final class Step1Result {
			private final ImportSymbols imports;
			private final Set<Path> inputFiles;
			private final Set<SharedLibFile> sharedLibs;

			public Step1Result(ImportSymbols imports, Set<Path> inputFiles, Set<SharedLibFile> sharedLibs) {
				this.imports = imports;
				this.inputFiles = inputFiles;
				this.sharedLibs = sharedLibs;
			}
		}

		private static final class SharedLibFile {
			private final Path path;
			private final SharedLibFormat format;

			private SharedLibFile(Path path, SharedLibFormat format) {
				this.path = path;
				this.format = format;
			}

			public HashCode hash(ImportSymbols imports) {
				return format.hash(path, imports);
			}
		}

		private interface ImportSymbols {
			void add(String e);
			boolean contains(String e);
			Set<Object> restrictToUnused();
		}

		private static final class CapturingImportSymbols implements ImportSymbols {
			private final SortedMap<Integer, Boolean> values = new TreeMap<>();

			public void add(String e) {
				values.put(e.hashCode(), Boolean.FALSE);
			}

			public boolean contains(String e) {
				return values.computeIfPresent(e.hashCode(), (__, ___) -> Boolean.TRUE) != null;
			}

			public Set<Object> restrictToUnused() {
				TreeSet<Object> result = new TreeSet<>();
				values.forEach((k, v) -> {
					if (!v) {
						result.add(k);
					}
				});
				return result;
			}
		}

		private static final class EmptyImportSymbols implements ImportSymbols {
			public void add(String e) {
				// do nothing
			}

			public boolean contains(String e) {
				return true;
			}

			public Set<Object> restrictToUnused() {
				return Collections.emptySet();
			}
		}

		private static final class Step2Result {
			private final List<HashCode> hashcode;
			private final Set<Path> inputFiles;
			private final Set<Object> unsused;

			public Step2Result(List<HashCode> hashcode, Set<Path> inputFiles, Set<Object> unsused) {
				this.hashcode = hashcode;
				this.inputFiles = inputFiles;
				this.unsused = unsused;
			}
		}

		@Inject
		public LinkAbiExtension(ObjectFactory objects, ProviderFactory providers) {
			hashes = objects.setProperty(Object.class);
			unresolved = objects.setProperty(Object.class);

			final Provider<AbiSnapshotter> useAbi = getLinkAbiSnapshotting().orElse(AbiSnapshotter.NONE);

			// The following steps are solely for ABI snapshotting.
			// The no ABI snapshotting configuration is done in the final step.

			// == Step 1 ==
			// transform all source into:
			//   - extract all import symbols
			//   -> warns on static lib
			//   -> warns on shared lib -> fucking weird, should not do this (revert to snapshot everything like before)
			// transform all libs into:
			//   - extract all import symbols from obj/static lib
			//   - each object file, add path in list of file to snapshot
			//   - each static lib, hash each object in the archive by static lib name
			//   - each shared lib, track a list of shared lib
			//   - bail out on any failure to parse -> wide ABI link snapshot
			ZipProvider.Factory zipProvider = objects.newInstance(ZipProvider.Factory.class);
			ListProperty<InFiles> inFiles = objects.listProperty(InFiles.class);
			inFiles.addAll(zipProvider.zip(useAbi, getSource().getElements(), (linkAbi, sources) -> linkAbi.equals(AbiSnapshotter.FULL_ABI) ? null : sources).map(SourceFiles::new).map(Collections::singletonList).orElse(Collections.emptyList()));
			inFiles.add(getLibs().getElements().map(it -> new LibraryFiles(it))); // using method reference here with configuration case cause error
			inFiles.disallowChanges();
			inFiles.finalizeValueOnRead();

			Provider<Step1Result> step1 = zipProvider.zip(useAbi, inFiles, (linkAbi, it) -> {
				ImportSymbols imports = linkAbi.equals(AbiSnapshotter.FULL_ABI) ? new EmptyImportSymbols() : new CapturingImportSymbols();
				Set<Path> inputFiles = new LinkedHashSet<>();
				Set<SharedLibFile> sharedLibs = new LinkedHashSet<>();
				for (InFiles files : it) {
					files.accept(new Step1Visitor() {
						@Override
						public void visitImport(String name) {
							System.out.println("Finding import: " + name);
							imports.add(name);
						}

						@Override
						public void visitAdditionalObjectFile(Path path) {
							inputFiles.add(path);
						}

						@Override
						public void visitSharedLibrary(Path path, SharedLibFormat format) {
							sharedLibs.add(new SharedLibFile(path, format));
						}

						@Override
						public void visitStaticLibrary(Path path) {
							inputFiles.add(path);
						}

						@Override
						public void visitImportLibrary(Path path) {
							sharedLibs.add(new SharedLibFile(path, SharedLibFormat.IMP));
						}
					});
				}
				return new Step1Result(imports, inputFiles, sharedLibs);
			});

			// == Step 2
			//  - wrap import symbols into trackable set
			//  - for each shared lib -> narrow exported ABI (mark used import symbols) -> generate HashCode for the shared lib
			//  - for failed shared lib parsing -> snapshot the whole file
			//  - drop any used import symbols to keep unused symbols
			Property<Step2Result> step2 = objects.property(Step2Result.class).value(step1.map(it -> {
				List<HashCode> hashcode = new ArrayList<>();
				for (SharedLibFile sharedLib : it.sharedLibs) {
					hashcode.add(sharedLib.hash(it.imports));
				}
				Set<Object> unsused = it.imports.restrictToUnused();
				return new Step2Result(hashcode, it.inputFiles, unsused);
			}));
			step2.finalizeValueOnRead();
			step2.disallowChanges();

			// == Step 3
			// split the data into:
			//  - @Input map of relative path to static lib to HashCode of object files
			//  - @Input set of unresolved symbols
			//  - @InputFiles set of failed parsed shared libs
			//  - @Input map of relative path to shared lib to HashCode of link ABI
			Property<Object> files = objects.property(Object.class).value(useAbi.flatMap(linkAbi -> {
				if (linkAbi.equals(AbiSnapshotter.NONE)) {
					return getLibs().getElements();
				}
				return step2.map(it -> (Object) it.inputFiles).orElse(getLibs().getElements());
			}));
			files.finalizeValueOnRead();
			getLibraryFiles().from(files);
			getUnresolvedImports().set(useAbi.flatMap(linkAbi -> {
				if (linkAbi.equals(AbiSnapshotter.NONE)) {
					return null;
				}
				return step2.map(it -> it.unsused);
			}));
			getUnresolvedImports().disallowChanges();
			getUnresolvedImports().finalizeValueOnRead();
			getHashes().set(useAbi.flatMap(linkAbi -> {
				if (linkAbi.equals(AbiSnapshotter.NONE)) {
					return null;
				}
				return step2.map(it -> it.hashcode);
			}));
			getHashes().disallowChanges();
			getHashes().finalizeValueOnRead();
		}

		@Internal
		public abstract ConfigurableFileCollection getSource();

		@Internal
		public abstract ConfigurableFileCollection getLibs();

		@Input
		@Optional
		public abstract Property<AbiSnapshotter> getLinkAbiSnapshotting();

		@Input
		@Optional
		protected SetProperty<Object> getUnresolvedImports() {
			return unresolved;
		}

		@Input
		@Optional
		protected SetProperty<Object> getHashes() {
			return hashes;
		}

		@Inject protected abstract ObjectFactory getObjects();

		@InputFiles
		@PathSensitive(PathSensitivity.NAME_ONLY) // because of Windows/MSVC, others use soname/installName
		protected abstract ConfigurableFileCollection getLibraryFiles();

		void close() {
			unresolved = null;
			hashes = null;
		}
	}
}
