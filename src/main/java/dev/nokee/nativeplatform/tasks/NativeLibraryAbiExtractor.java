package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
import java.nio.file.Path;

interface NativeLibraryAbiExtractor {
	Object extract(Path library);
}
