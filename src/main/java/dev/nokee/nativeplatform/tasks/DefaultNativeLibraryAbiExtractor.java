package dev.nokee.nativeplatform.tasks;

import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.AbiBinaryHashCode;
import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.HasMembers;
import dev.nokee.nativeplatform.tasks.AbiBinaryHasher.Type;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.function.Consumer;

final class DefaultNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};
	private static final byte[] AR_MAGIC = {0x21, 0x3c, 0x61, 0x72, 0x63, 0x68, 0x3e, 0x0a}; // !<arch>\n

	private ElfBinaryHasher elfHasher;
	private MachOBinaryHasher machOHasher;
	private ArchiveBinaryHasher archiveHasher;
	private ObjectFileImportReader objectImportReader;

	private final ByteBuffer buffer = ByteBuffer.allocate(8);

	/**
	 * The model for a file we could not classify (or an import source we could not parse). It carries its
	 * location so the link can byte-snapshot it, and — being {@link AbiBinaryHasher.Unknown} — forces the
	 * link to fall back to the full ABI (it might be an import source with unknown imports).
	 */
	private static final class UnknownHashCode implements AbiBinaryHashCode, AbiBinaryHasher.Unknown, AbiBinaryHasher.HasLocation {
		private final File location;

		private UnknownHashCode(Path location) {
			this.location = location.toFile();
		}

		@Override
		public Type type() {
			return Type.UNKNOWN;
		}

		@Override
		public File location() {
			return location;
		}
	}

	/**
	 * Wraps an archive model so it carries its file location (the hashers read a {@link FileChannel} and do
	 * not know the path). A static library is byte-snapshotted, so the link needs the location alongside the
	 * members it exposes.
	 */
	private static final class LocatedArchive implements AbiBinaryHashCode, HasMembers, AbiBinaryHasher.HasLocation {
		private final AbiBinaryHashCode delegate;
		private final File location;

		private LocatedArchive(AbiBinaryHashCode delegate, Path location) {
			this.delegate = delegate;
			this.location = location.toFile();
		}

		@Override
		public Type type() {
			return delegate.type();
		}

		@Override
		public Set<AbiBinaryHashCode> getMembers() {
			return ((HasMembers) delegate).getMembers();
		}

		@Override
		public File location() {
			return location;
		}
	}

	/**
	 * The model for a shared library we could identify but not parse. It is byte-snapshotted (via its
	 * location) and is <em>not</em> {@link AbiBinaryHasher.Unknown}, so it is confined to itself — the
	 * other shared libraries in the link keep narrowing. It exposes no exports, so the link snapshots the
	 * whole file rather than an ABI subset.
	 */
	private static final class CorruptSharedLibrary implements AbiBinaryHashCode, AbiBinaryHasher.HasLocation {
		private final File location;

		private CorruptSharedLibrary(Path location) {
			this.location = location.toFile();
		}

		@Override
		public Type type() {
			return Type.DYNAMIC_LIB;
		}

		@Override
		public File location() {
			return location;
		}
	}

	@Override
	public AbiBinaryHashCode hash(Path library) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			if (channel.size() < 8) {
				return new UnknownHashCode(library);
			}
			byte[] header = BinaryUtils.readInto(channel, 0, buffer, 8).array();

			AbiBinaryHasher hasher;
			if (isElfMagic(header)) {
				hasher = elfHasher();
			} else if (isMachOMagic(header)) {
				hasher = machOHasher();
			} else if (isArMagic(header)) {
				hasher = archiveHasher();
			} else {
				return new UnknownHashCode(library);
			}

			return attachLocation(hasher.hash(channel), library);
		} catch (UnreadableSharedLibraryException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			// A shared library we identified but could not parse: byte-snapshot the whole file, yet keep
			// narrowing the other libraries — it is a shared library, not an import source.
			return new CorruptSharedLibrary(library);
		} catch (NotASharedLibraryException | IllegalArgumentException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			// Not the ABI we expected, or a member we could not read: conservatively treat as unknown.
			return new UnknownHashCode(library);
		} catch (IOException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public AbiBinaryHashCode hashObject(Path library) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			return objectImportReader().hash(channel, 0, channel.size());
		} catch (IllegalArgumentException e) {
			System.out.println("Exception for '" + library + "'");
			// Object we could not parse: its imports are unknown, so narrowing must be disabled.
			e.printStackTrace();
			return new UnknownHashCode(library);
		} catch (IOException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void visitImports(Path library, Consumer<? super Object> visitor) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			objectImportReader().visitImports(channel, 0, channel.size(), visitor);
		} catch (IllegalArgumentException e) {
			System.out.println("Exception for '" + library + "'");
			// Object we could not parse: its imports are unknown, so narrowing must be disabled.
			e.printStackTrace();
			throw new RuntimeException(e);
		} catch (IOException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Override
	public void visit(Path library, StaticOrSharedVisitor visitor) {
		try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
			if (channel.size() < 8) {
				visitor.visitUnknownLib(library);
			}
			byte[] header = BinaryUtils.readInto(channel, 0, buffer, 8).array();

			AbiBinaryHasher hasher;
			if (isElfMagic(header)) {
				visitor.visitSharedLib((AbiBinaryHasher.HasExportSymbols) elfHasher().hash(channel));
			} else if (isMachOMagic(header)) {
				visitor.visitSharedLib((AbiBinaryHasher.HasExportSymbols) machOHasher().hash(channel));
			} else if (isArMagic(header)) {
				archiveHasher().visitImports(channel, visitor::visitImports);
				visitor.visitStaticLib(library);
				// TODO: If cannot read static lib bail to wide ABI link snapshot
			} else {
				visitor.visitUnknownLib(library);
			}

//			return attachLocation(hasher.hash(channel), library);
		} catch (UnreadableSharedLibraryException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			// A shared library we identified but could not parse: byte-snapshot the whole file, yet keep
			// narrowing the other libraries — it is a shared library, not an import source.
			visitor.visitUnknownLib(library);
		} catch (NotASharedLibraryException | IllegalArgumentException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			// Not the ABI we expected, or a member we could not read: conservatively treat as unknown.
//			return new UnknownHashCode(library);
			throw e;
		} catch (IOException e) {
			System.out.println("Exception for '" + library + "'");
			e.printStackTrace();
			throw new UncheckedIOException(e);
		}
	}

	// Archives are byte-snapshotted, so they must carry their location; export/import symbol models do not.
	private static AbiBinaryHashCode attachLocation(AbiBinaryHashCode model, Path library) {
		if (model instanceof HasMembers) {
			return new LocatedArchive(model, library);
		}
		return model;
	}

	private AbiBinaryHasher elfHasher() {
		if (elfHasher == null) {
			elfHasher = new ElfBinaryHasher();
		}
		return elfHasher;
	}

	private AbiBinaryHasher machOHasher() {
		if (machOHasher == null) {
			machOHasher = new MachOBinaryHasher();
		}
		return machOHasher;
	}

	private ArchiveBinaryHasher archiveHasher() {
		if (archiveHasher == null) {
			archiveHasher = new ArchiveBinaryHasher(objectImportReader());
		}
		return archiveHasher;
	}

	private ObjectFileImportReader objectImportReader() {
		if (objectImportReader == null) {
			objectImportReader = new ObjectFileImportReader();
		}
		return objectImportReader;
	}

	private static boolean isElfMagic(byte[] h) {
		return h[0] == ELF_MAGIC[0] && h[1] == ELF_MAGIC[1]
			&& h[2] == ELF_MAGIC[2] && h[3] == ELF_MAGIC[3];
	}

	private static boolean isMachOMagic(byte[] h) {
		int m = asInt(h, 0);
		return m == 0xFEEDFACE || m == 0xCEFAEDFE
			|| m == 0xFEEDFACF || m == 0xCFFAEDFE
			|| m == 0xCAFEBABE || m == Integer.reverseBytes(0xCAFEBABE);
	}

	private static boolean isArMagic(byte[] h) {
		for (int i = 0; i < AR_MAGIC.length; i++) {
			if (h[i] != AR_MAGIC[i]) return false;
		}
		return true;
	}

	private static int asInt(byte[] b, int offset) {
		return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
			| ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
	}
}
