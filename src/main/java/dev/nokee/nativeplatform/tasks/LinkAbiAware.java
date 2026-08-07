package dev.nokee.nativeplatform.tasks;

import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
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

import static dev.nokee.nativeplatform.tasks.ArchiveBlob.skipSymbolTables;
import static dev.nokee.nativeplatform.tasks.ElfBlob.ET_DYN;
import static dev.nokee.nativeplatform.tasks.ElfBlob.ET_REL;
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

	abstract /*final*/ class LinkAbiExtension {
		private SetProperty<Object> unresolved;
		private SetProperty<Object> hashes;

		private enum AbiSnapshotter {
			FULL_ABI,
			NARROW_ABI
		}

		private static final ElfBinaryHasher elf = new ElfBinaryHasher();
		private static final MachOBinaryHasher macho = new MachOBinaryHasher();

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
						} else {
							throw new UnsupportedOperationException("Invalid file with signature '" + HexFormat.of().formatHex(hdr.array()) + "' on file '" + path + "'");
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}

			protected abstract void visitElf(Path path, ElfBlob blob, Step1Visitor visitor);
			protected void visitMachO(Path path, MachOBlob blob, Step1Visitor visitor) {
				if (blob instanceof MachOBlob.MachOUniversalBlob) {
					for (MachOBlob.MachOImageBlob architecture : ((MachOBlob.MachOUniversalBlob) blob).architectures()) {
						visitMachO(path, architecture, visitor);
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
							public void visitSoname(String soname) {
								hasher.putString(soname);
							}

							@Override
							public void visitExport(String name, int binding) {
								if (imports.contains(name)) {
									hasher.putString(name);
									hasher.putInt(binding);
								}
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
								hasher.putString(installName);
							}

							@Override
							public void visitExportSymbol(String name, boolean weakBinding) {
								if (imports.contains(name)) {
									hasher.putString(name);
									hasher.putBoolean(weakBinding);
								}
							}
						});
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					return hasher.hash();
				}
			};

			public abstract HashCode hash(Path path, ImportSymbols imports);
		}

		private interface Step1Visitor {
			void visitImport(String name);
			void visitAdditionalObjectFile(Path path);
			void visitSharedLibrary(Path path, SharedLibFormat format);
			void visitStaticLibrary(Path path);
		}


		// Source files (aka object files) are snapshotted as input files
		//   We only need to warn strange usecases (like adding static or shared lib to the sources)
		private static final class SourceFiles extends InFiles {
			private SourceFiles(Set<FileSystemLocation> elements) {
				super(elements);
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
				blob.members().forEach(skipSymbolTables(member -> {
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
					} else {
						throw new RuntimeException("unknown member '" + member.identifier() + "' from '" + path + "'");
					}
				}));
			}
		}

		// However, libs files need to be processed to correctly snapshot what is needed
		private static final class LibraryFiles extends InFiles {
			public LibraryFiles(Set<FileSystemLocation> elements) {
				super(elements);
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
				visitor.visitStaticLibrary(path);

				ByteBuffer hdr = ByteBuffer.allocate(8);
				blob.members().forEach(skipSymbolTables(member -> {
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
					} else {
						throw new RuntimeException("unknown member '" + member.identifier() + "' from '" + path + "'");
					}
				}));
			}
		}

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

		private static final class ImportSymbols {
			private final SortedMap<String, Boolean> values = new TreeMap<>();

			public void add(String e) {
				values.put(e, Boolean.FALSE);
			}

			public boolean contains(String e) {
				return values.computeIfPresent(e, (__, ___) -> Boolean.TRUE) != null;
			}

			public Set<String> restrictToUnused() {
				TreeSet<String> result = new TreeSet<>();
				values.forEach((k, v) -> {
					if (!v) {
						result.add(k);
					}
				});
				return result;
			}
		}

		private static final class Step2Result {
			private final List<HashCode> hashcode;
			private final Set<Path> inputFiles;
			private final Set<String> unsused;

			public Step2Result(List<HashCode> hashcode, Set<Path> inputFiles, Set<String> unsused) {
				this.hashcode = hashcode;
				this.inputFiles = inputFiles;
				this.unsused = unsused;
			}
		}

		@Inject
		public LinkAbiExtension(ObjectFactory objects) {
			hashes = objects.setProperty(Object.class);
			unresolved = objects.setProperty(Object.class);


			final Provider<Boolean> useAbi = getUseNormalizedAbi().orElse(false);

			// == Step 1
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
			ListProperty<InFiles> inFiles = objects.listProperty(InFiles.class);
			inFiles.add(getSource().getElements().map(SourceFiles::new));
			inFiles.add(getLibs().getElements().map(it -> new LibraryFiles(it))); // using method reference here with configuration case cause error
			inFiles.disallowChanges();
			inFiles.finalizeValueOnRead();

			Provider<Step1Result> step1 = inFiles.map(it -> {
				ImportSymbols imports = new ImportSymbols();
				Set<Path> inputFiles = new LinkedHashSet<>();
				Set<SharedLibFile> sharedLibs = new LinkedHashSet<>();
				for (InFiles files : it) {
					files.accept(new Step1Visitor() {
						@Override
						public void visitImport(String name) {
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
				Set<String> unsused = it.imports.restrictToUnused();
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
			getLibraryFiles().from(step2.map(it -> it.inputFiles));
			getUnresolvedImports().set(step2.map(it -> it.unsused));
			getUnresolvedImports().disallowChanges();
			getUnresolvedImports().finalizeValueOnRead();
			getHashes().set(step2.map(it -> it.hashcode));
			getHashes().disallowChanges();
			getHashes().finalizeValueOnRead();
		}

		@Internal
		public abstract ConfigurableFileCollection getSource();

		@Internal
		public abstract ConfigurableFileCollection getLibs();

		@Input
		@Optional
		public abstract Property<Boolean> getUseNormalizedAbi();

		@Input
		protected SetProperty<Object> getUnresolvedImports() {
			return unresolved;
		}

		@Input
		protected SetProperty<Object> getHashes() {
			return hashes;
		}

		@Inject protected abstract ObjectFactory getObjects();

//		@Input // This pattern is @Nested while respecting the provider knowledge
//		// Note that this pattern must split the "@InputFiles"/"@OutputFiles" from the "@Input" values as we don't have real @Nested
//		protected SetProperty<Map<String, Object>> getLibraryAbiModelsProps() {
//			return libraryAbiModelsProps;
//		}

		@InputFiles
		protected abstract ConfigurableFileCollection getLibraryFiles();

		void close() {
			unresolved = null;
			hashes = null;
		}
	}
}
