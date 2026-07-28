package dev.nokee.nativeplatform.tasks;

import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	AbiBinaryHasher.AbiBinaryHashCode hash(Path library);
	AbiBinaryHasher.AbiBinaryHashCode hashObject(Path library);
}
