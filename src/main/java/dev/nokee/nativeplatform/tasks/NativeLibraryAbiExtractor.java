package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	@Nullable AbiEntry extract(Path library);
}
