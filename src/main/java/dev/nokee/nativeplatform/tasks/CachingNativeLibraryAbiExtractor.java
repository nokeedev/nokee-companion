package dev.nokee.nativeplatform.tasks;

import javax.inject.Inject;
import java.nio.file.Path;

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
		return cache.find(library, () -> extractor.hashObject(library));
	}
}
