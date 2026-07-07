package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	@Nullable AbiModel extract(Path library);
}
