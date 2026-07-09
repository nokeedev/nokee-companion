package dev.nokee.nativeplatform.tasks;

import javax.inject.Inject;
import java.io.File;

/*private*/ abstract /*final*/ class AbiExtractorService {
	private final CachingNativeLibraryAbiExtractor extractor;

	@Inject
	public AbiExtractorService(CachingNativeLibraryAbiExtractor extractor) {
		this.extractor = extractor;
	}

	// File or AbiModel
	Object extract(File library) {
		Object entry = doExtract(library);
		if (entry == null) {
			return library;
		} else {
			return entry;
		}
	}

	private Object doExtract(File library) {
		try {
			return extractor.extract(library.toPath());
		} catch (Exception ignored) {
			return null;
		}
	}
}
