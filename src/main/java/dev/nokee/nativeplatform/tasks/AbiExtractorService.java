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
		return extractor.extract(library.toPath());
	}
}
