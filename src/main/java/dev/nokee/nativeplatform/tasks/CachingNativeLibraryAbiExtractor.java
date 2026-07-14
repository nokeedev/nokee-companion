package dev.nokee.nativeplatform.tasks;

import javax.annotation.Nullable;
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

	public Object hash(Path library) {
		return cache.find(library, () -> extractor.hash(library));
	}
}
