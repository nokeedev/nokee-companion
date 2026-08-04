package dev.nokee.nativeplatform.tasks;

import java.nio.file.Path;
import java.util.function.Consumer;

interface NativeLibraryAbiExtractor {
	AbiBinaryHasher.AbiBinaryHashCode hash(Path library);
	AbiBinaryHasher.AbiBinaryHashCode hashObject(Path library);

	void visitImports(Path library, Consumer<? super Object> visitor);
	void visit(Path library, StaticOrSharedVisitor visitor);
}
