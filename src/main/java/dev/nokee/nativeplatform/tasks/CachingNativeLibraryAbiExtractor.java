package dev.nokee.nativeplatform.tasks;

import org.gradle.api.model.ObjectFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.nio.file.Path;

abstract /*final*/ class CachingNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private final LinkAbiCache cache;
	private final DefaultNativeLibraryAbiExtractor extractor;

	@Inject
	public CachingNativeLibraryAbiExtractor(LinkAbiCache cache, ObjectFactory objects) {
		this.cache = cache;
		this.extractor = new DefaultNativeLibraryAbiExtractor(objects);
	}

	public @Nullable AbiEntry extract(Path library) {
		return cache.find(library, () -> extractor.extract(library));
	}
}
