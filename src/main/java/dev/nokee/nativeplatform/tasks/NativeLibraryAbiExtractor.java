package dev.nokee.nativeplatform.tasks;

import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	Object hash(Path library);
}
