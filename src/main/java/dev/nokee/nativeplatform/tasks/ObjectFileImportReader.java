package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Reads the imports of one object image by dispatching on its magic to the ELF, Mach-O or COFF import
 * reader. Used as the per-member reader of an {@link ArchiveBinaryHasher} and directly for standalone
 * object files, so the archive walk and the object-file path make no assumption about the object format.
 * An image whose format is not recognized throws {@link IllegalArgumentException}.
 */
final class ObjectFileImportReader implements AbiObjectHasher {
	private final ElfBinaryHasher elfReader;
	private final MachOBinaryHasher machOReader;
	private final CoffImportReader coffReader;

	ObjectFileImportReader() {
		this.elfReader = new ElfBinaryHasher();
		this.machOReader = new MachOBinaryHasher();
		this.coffReader = new CoffImportReader();
	}

	@Override
	public AbiBinaryHasher.AbiBinaryHashCode hash(BSource source) throws IOException {
		if (source.size() < 4) {
			throw new IllegalArgumentException("object image too small to identify");
		}
		byte[] magic = BinaryUtils.readBytes(source, 0, 4);
		if (ElfBlob.isElfMagic(magic)) {
			return elfReader.hash(source);
		}
		if (MachOBlob.isMachOMagic(magic)) {
			return machOReader.hash(source);
		}
		// COFF has no strong magic; the COFF reader validates the machine type and rejects anything else.
		return coffReader.hash(source);
	}

	@Override
	public void visitImports(BSource source, Consumer<? super Object> visitor) throws IOException {
		if (source.size() < 4) {
			throw new IllegalArgumentException("object image too small to identify");
		}
		byte[] magic = BinaryUtils.readBytes(source, 0, 4);
		if (ElfBlob.isElfMagic(magic)) {
			elfReader.hash(source, visitor);
		} else if (MachOBlob.isMachOMagic(magic)) {
			machOReader.visitImports(source, visitor);
//			return machOReader.hash(channel, base, size);
		} else {
			// COFF has no strong magic; the COFF reader validates the machine type and rejects anything else.
//		return coffReader.hash(channel, base, size);
			throw new UnsupportedOperationException("magic " + Arrays.toString(magic));
		}
	}
}
