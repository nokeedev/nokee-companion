package dev.nokee.nativeplatform.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a Windows import library reports about the exports it stands for, recorded as its members
 * are walked.
 *
 * <p>An import library has no reader of its own: it is an archive whose members are read through
 * {@link MicrosoftImportObjectBlob#onlyImportObjects(java.util.function.Consumer)}, which is what
 * {@code LinkAbiAware} hashes an import library with. The library also holds ordinary COFF objects —
 * the import descriptor, its terminator and the thunk data — and none of them is an export, so what
 * this records is also what the walk leaves out.
 */
final class ImportLibraryAbi {
	static final int IMAGE_FILE_MACHINE_I386 = 0x014C;
	static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;

	static ImportLibraryAbi read(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path)) {
			ImportLibraryAbi result = new ImportLibraryAbi();
			ArchiveBlob archive = ArchiveBlob.parse(new BSource(channel));
			try (ArchiveBlob.ArchiveMembers members = archive.members()) {
				members.forEach(MicrosoftImportObjectBlob.onlyImportObjects(result::visitImportObject));
			}
			return result;
		}
	}

	private final List<Export> exports = new ArrayList<>();

	List<Export> exports() {
		return Collections.unmodifiableList(exports);
	}

	private void visitImportObject(MicrosoftImportObjectBlob blob) {
		// Read every field here rather than keep the blob: it maps the names it reads, and the walk
		// closes it as soon as this returns.
		exports.add(new Export(blob.symbolName(), blob.dllName(), blob.machine(), blob.version(),
			blob.type(), blob.nameType(), ordinalOf(blob)));
	}

	/**
	 * The ordinal the symbol is exported at, or {@literal null} when it is exported by name. The header
	 * holds one field for both, and which one it carries follows the name type: an export by name puts
	 * a hint there, which only tells the loader where to start looking in the DLL's name table and so
	 * says nothing about what the DLL exports.
	 */
	private static Integer ordinalOf(MicrosoftImportObjectBlob blob) {
		return blob.nameType() == MicrosoftImportObjectBlob.IMPORT_OBJECT_ORDINAL ? blob.ordinalOrHint() : null;
	}

	/**
	 * @param symbolName  the symbol a consumer references, decorated the way the target decorates it
	 * @param dllName     the DLL that defines it
	 * @param machine     an {@code IMAGE_FILE_MACHINE_*} value
	 * @param type        an {@code IMPORT_OBJECT_CODE}, {@code _DATA} or {@code _CONST} value
	 * @param nameType    an {@code IMPORT_OBJECT_ORDINAL}, {@code _NAME}, {@code _NAME_NOPREFIX}, … value
	 * @param ordinal     the ordinal it is exported at, or {@literal null} when it is exported by name
	 */
	record Export(String symbolName, String dllName, int machine, int version, int type, int nameType,
			Integer ordinal) {}
}
