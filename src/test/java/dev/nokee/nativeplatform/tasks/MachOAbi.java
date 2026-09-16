package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything {@link MachOBinaryHasher} reports about a Mach-O shared library, recorded as it visits one.
 *
 * <p>Each part is a list because a universal binary holds an image per architecture and the reader
 * walks every one of them, so a test on a universal binary sees each part once per image.
 */
final class MachOAbi implements MachOBinaryHasher.ExportOrInstallNameVisitor {
	static final int CPU_TYPE_X86_64 = 0x01000007;
	static final int CPU_TYPE_ARM64 = 0x0100000C;

	static final int CPU_SUBTYPE_X86_64_ALL = 3;
	static final int CPU_SUBTYPE_ARM64_ALL = 0;

	static MachOAbi read(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			MachOAbi result = new MachOAbi();
			new MachOBinaryHasher().visitSharedLib(MachOBlob.parse(new BSource(channel)), result);
			return result;
		}
	}

	private final List<Export> exports = new ArrayList<>();
	private final List<String> installNames = new ArrayList<>();
	private final List<Integer> cpuTypes = new ArrayList<>();
	private final List<Integer> cpuSubTypes = new ArrayList<>();

	/** The names the images record for themselves, one {@code LC_ID_DYLIB} per image. */
	List<String> installNames() {
		return Collections.unmodifiableList(installNames);
	}

	/** The {@code cputype} of each image, a {@code CPU_TYPE_*} value. */
	List<Integer> cpuTypes() {
		return Collections.unmodifiableList(cpuTypes);
	}

	/** The {@code cpusubtype} of each image, a {@code CPU_SUBTYPE_*} value. */
	List<Integer> cpuSubTypes() {
		return Collections.unmodifiableList(cpuSubTypes);
	}

	List<Export> exports() {
		return Collections.unmodifiableList(exports);
	}

	@Override
	public void visitInstallName(String installName) {
		installNames.add(installName);
	}

	@Override
	public void visitCpuType(int cputype) {
		cpuTypes.add(cputype);
	}

	@Override
	public void visitCpuSubType(int cpusubtype) {
		cpuSubTypes.add(cpusubtype);
	}

	@Override
	public void visitExportSymbol(String name, boolean weakBinding) {
		exports.add(new Export(name, weakBinding));
	}

	/** @param weakDefinition whether the definition carries {@code N_WEAK_DEF} */
	record Export(String name, boolean weakDefinition) {
		static Export strong(String name) {
			return new Export(name, false);
		}

		static Export weak(String name) {
			return new Export(name, true);
		}
	}
}
