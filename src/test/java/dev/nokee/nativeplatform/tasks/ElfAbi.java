package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything {@link ElfBinaryHasher} reports about an ELF shared library, recorded as it visits one.
 *
 * <p>This is the visitor the reader is written against, so a test reads the same callbacks the hasher
 * does. What it records is shaped for assertions alone: it holds no hash, and it keeps each part under
 * the name the format gives it.
 */
final class ElfAbi implements ElfBinaryHasher.SonameAndExportVisitor {
	static final int STB_GLOBAL = 1;
	static final int STB_WEAK = 2;

	static final int STT_OBJECT = 1;
	static final int STT_FUNC = 2;
	static final int STT_TLS = 6;

	static final int EM_X86_64 = 62;
	static final int EM_AARCH64 = 183;

	static final int ELFOSABI_NONE = 0;

	/** What a part records before the reader has visited it, so an unvisited part reads as such. */
	static final int NOT_VISITED = -1;

	static ElfAbi read(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			ElfAbi result = new ElfAbi();
			new ElfBinaryHasher().visitSharedLib(ElfBlob.parse(new BSource(channel)), result);
			return result;
		}
	}

	private final List<Export> exports = new ArrayList<>();
	private final List<String> sonames = new ArrayList<>();
	private int architecture = NOT_VISITED;
	private int osAbi = NOT_VISITED;
	private int abiVersion = NOT_VISITED;
	private int type = NOT_VISITED;

	/** The {@code e_machine} of the image, an {@code EM_*} value. */
	int architecture() {
		return architecture;
	}

	/** The {@code EI_OSABI} of the image, an {@code ELFOSABI_*} value. */
	int osAbi() {
		return osAbi;
	}

	/** The {@code EI_ABIVERSION} of the image. */
	int abiVersion() {
		return abiVersion;
	}

	/** The {@code e_type} of the image, which the reader leaves unvisited. */
	int type() {
		return type;
	}

	/** The names the library records for itself, which is at most one {@code DT_SONAME}. */
	List<String> sonames() {
		return Collections.unmodifiableList(sonames);
	}

	List<Export> exports() {
		return Collections.unmodifiableList(exports);
	}

	@Override
	public void visitArchitecture(int arch) {
		this.architecture = arch;
	}

	@Override
	public void visitOsAbi(int osabi) {
		this.osAbi = osabi;
	}

	@Override
	public void visitAbiVersion(int abiversion) {
		this.abiVersion = abiversion;
	}

	@Override
	public void visitType(int type) {
		this.type = type;
	}

	@Override
	public void visitSoname(String soname) {
		sonames.add(soname);
	}

	@Override
	public void visitExport(String name, int info, long size, String version, boolean defaultVersion) {
		// st_info packs two fields the assertions keep apart: the binding in its high nibble and the
		// type in its low one.
		exports.add(new Export(name, info >> 4, info & 0xF, size, version, defaultVersion));
	}

	/**
	 * @param binding         an {@code STB_*} value, the high nibble of {@code st_info}
	 * @param type            an {@code STT_*} value, the low nibble of {@code st_info}
	 * @param version         the version the symbol is defined at, or {@literal null} when it carries none
	 * @param defaultVersion  whether an unversioned reference binds to this definition
	 */
	record Export(String name, int binding, int type, long size, String version, boolean defaultVersion) {}
}
