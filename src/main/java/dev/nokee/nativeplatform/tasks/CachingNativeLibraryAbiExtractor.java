package dev.nokee.nativeplatform.tasks;

import javax.inject.Inject;
import java.nio.file.Path;
import java.util.function.Consumer;

abstract /*final*/ class CachingNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private final LinkAbiCache cache;
	private final DefaultNativeLibraryAbiExtractor extractor;

	@Inject
	public CachingNativeLibraryAbiExtractor(LinkAbiCache cache) {
		this.cache = cache;
		this.extractor = new DefaultNativeLibraryAbiExtractor();
	}

	public AbiBinaryHasher.AbiBinaryHashCode hash(Path library) {
		return cache.find(library, () -> extractor.hash(library));
	}

	public AbiBinaryHasher.AbiBinaryHashCode hashObject(Path library) {
		return extractor.hashObject(library);
	}

	@Override
	public void visitImports(Path library, Consumer<? super Object> visitor) {
		extractor.visitImports(library, visitor);
	}

	@Override
	public void visit(Path library, StaticOrSharedVisitor visitor) {
		extractor.visit(library, visitor);
	}
}
