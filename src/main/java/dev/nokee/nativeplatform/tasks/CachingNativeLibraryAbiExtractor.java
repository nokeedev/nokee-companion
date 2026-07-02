package dev.nokee.nativeplatform.tasks;

import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.vfs.FileSystemAccess;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.nio.file.Path;

abstract /*final*/ class CachingNativeLibraryAbiExtractor implements NativeLibraryAbiExtractor {
	private final FileSystemAccess fileAccess;
	private final LinkAbiCache cache;
	private final DefaultNativeLibraryAbiExtractor extractor;

	@Inject
	public CachingNativeLibraryAbiExtractor(FileSystemAccess fileAccess, LinkAbiCache cache, ObjectFactory objects) {
		this.fileAccess = fileAccess;
		this.cache = cache;
		this.extractor = new DefaultNativeLibraryAbiExtractor(objects);
	}

	public @Nullable AbiEntry extract(Path library) {
		HashCode hash = fileAccess.readRegularFileContentHash(library.toString()).orElseThrow(RuntimeException::new);
		return cache.find(hash.toString(), () -> extractor.extract(library));
	}
}
