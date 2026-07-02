package dev.nokee.nativeplatform.tasks;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.AbstractMap;

/*private*/ abstract /*final*/ class AbiExtractorService {
	private final CachingNativeLibraryAbiExtractor extractor;

	@Inject
	public AbiExtractorService(CachingNativeLibraryAbiExtractor extractor) {
		this.extractor = extractor;
	}

	// File or Entry<String, AbiModel>
	Object extract(File library, Path projectRoot) {
		AbiEntry entry = doExtract(library);
		if (entry == null) {
			return library;
		} else {
			String identity = entry.soname != null ? entry.soname
				: projectRoot.relativize(library.toPath()).toString();
			return new AbstractMap.SimpleImmutableEntry<>(identity, entry.model);
		}
	}

	private AbiEntry doExtract(File library) {
		try {
			return extractor.extract(library.toPath());
		} catch (Exception ignored) {
			return null;
		}
	}
}
