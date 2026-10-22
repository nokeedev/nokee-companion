package dev.nokee.nativeplatform.tasks;

import dev.nokee.commons.gradle.provider.ZipProvider;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.hash.PrimitiveHasher;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.nokee.nativeplatform.tasks.ElfBlob.*;
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
		private SetProperty<Object> hashes;

		private static final ElfBinaryHasher elf = new ElfBinaryHasher();
		private static final MachOBinaryHasher macho = new MachOBinaryHasher();

		private static final class InFiles {
			private final Set<FileSystemLocation> elements;

			public InFiles(Set<FileSystemLocation> elements) {
				this.elements = elements;
			}

			public void accept(Step1Visitor visitor) {
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
						} else {
							visitor.visitInputFile(path);
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}

			private void visitElf(Path path, ElfBlob blob, Step1Visitor visitor) {
				switch (blob.e_type()) {
					case ET_REL:
						visitor.visitAdditionalObjectFile(path);
						break;
					case ET_DYN:
						visitor.visitSharedLibrary(path, blob);
						break;
					default: throw new UnsupportedOperationException("invalid elf type '" + blob.e_type() + "' on file '" + path + "'");
				}
			}

			private void visitMachO(Path path, MachOBlob blob, Step1Visitor visitor) {
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

			private void visitMachO(Path path, MachOImageBlob blob, Step1Visitor visitor) {
				switch (blob.filetype()) {
					case MH_OBJECT:
						visitor.visitAdditionalObjectFile(path);
						break;
					case MH_DYLIB:
					case MH_DYLIB_STUB:
						visitor.visitSharedLibrary(path, blob);
						break;
					default: throw new UnsupportedOperationException("invalid mach-o type '" + blob.filetype() + "' on file '" + path + "'");
				}
			}
		}

		private interface Step1Visitor {
			void visitAdditionalObjectFile(Path path);
			void visitSharedLibrary(Path path, ElfBlob blob);
			void visitSharedLibrary(Path path, MachOImageBlob blob);
			void visitInputFile(Path path);
		}


		private static final class Step1Result {
			private final Set<Path> inputFiles;
			private final List<HashCode> hashcodes;

			public Step1Result(Set<Path> inputFiles, List<HashCode> hashcodes) {
				this.inputFiles = inputFiles;
				this.hashcodes = hashcodes;
			}
		}

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

		@Inject
		public LinkAbiExtension(ObjectFactory objects, ProviderFactory providers) {
			hashes = objects.setProperty(Object.class);

			final Provider<AbiSnapshotter> useAbi = getLinkAbiSnapshotting().orElse(AbiSnapshotter.NONE);

			ZipProvider.Factory zipProvider = objects.newInstance(ZipProvider.Factory.class);
			Property<Object> obj = objects.property(Object.class);
			obj.set(zipProvider.zip(useAbi, getLibs().getElements(), (linkAbi, libs) -> {
				if (linkAbi.equals(AbiSnapshotter.NONE)) {
					return libs;
				}
				Set<Path> inputFiles = new LinkedHashSet<>();
				List<HashCode> hashcode = new ArrayList<>();

				new InFiles(libs).accept(new Step1Visitor() {
					@Override
					public void visitAdditionalObjectFile(Path path) {
						inputFiles.add(path);
					}

					@Override
					public void visitSharedLibrary(Path path, ElfBlob blob) {
						try {
							PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
							elf.visitSharedLib(blob, new ElfBinaryHasher.SonameAndExportVisitor() {
								@Override
								public void visitArchitecture(int arch) {
									hasher.putInt(arch);
								}

								@Override
								public void visitOsAbi(int osabi) {
									hasher.putInt(osabi);
								}

								@Override
								public void visitSoname(String soname) {
									hasher.putString(soname);
								}

								@Override
								public void visitExport(String name, int info, long size, String version, boolean defaultVersion) {
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

								@Override
								public void visitAbiVersion(int abiversion) {
									hasher.putInt(abiversion);
								}

								@Override
								public void visitType(int type) {
									hasher.putInt(type);
								}
							});
							hashcode.add(hasher.hash());
						} catch (IOException e) {
							inputFiles.add(path);
						}
					}

					@Override
					public void visitSharedLibrary(Path path, MachOImageBlob blob) {
						PrimitiveHasher hasher = Hashing.newPrimitiveHasher();
						macho.visitSharedLib(blob, new MachOBinaryHasher.ExportOrInstallNameVisitor() {
							@Override
							public void visitInstallName(String installName) {
								hasher.putString(installName);
							}

							@Override
							public void visitExportSymbol(String name, boolean weakBinding) {
								hasher.putString(name);
								hasher.putBoolean(weakBinding);
							}

							@Override
							public void visitCpuType(int cputype) {
								hasher.putInt(cputype);
							}

							@Override
							public void visitCpuSubType(int cpusubtype) {
								hasher.putInt(cpusubtype);
							}
						});
						hashcode.add(hasher.hash());
					}

					@Override
					public void visitInputFile(Path path) {
						inputFiles.add(path);
					}
				});

				return new Step1Result(inputFiles, hashcode);
			}));
			obj.finalizeValueOnRead();
			obj.disallowChanges();

			getLibraryFiles().from(obj.map(it -> it instanceof Step1Result ? ((Step1Result) it).inputFiles : it));
			getHashes().set(obj.map(it -> it instanceof Step1Result ? ((Step1Result) it).hashcodes : null));
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
		protected SetProperty<Object> getHashes() {
			return hashes;
		}

		@Inject protected abstract ObjectFactory getObjects();

		@InputFiles
		@PathSensitive(PathSensitivity.NAME_ONLY) // because of Windows/MSVC, others use soname/installName
		protected abstract ConfigurableFileCollection getLibraryFiles();

		void close() {
			hashes = null;
		}
	}
}
