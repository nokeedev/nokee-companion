package dev.nokee.nativeplatform.tasks;

import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	AbiEntry extract(Path library);
}
