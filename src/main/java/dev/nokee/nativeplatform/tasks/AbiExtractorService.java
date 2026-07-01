package dev.nokee.nativeplatform.tasks;

import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;

/*private*/ abstract /*final*/ class AbiExtractorService {
	private final CachingNativeLibraryAbiExtractor extractor;

	@Inject
	public AbiExtractorService(CachingNativeLibraryAbiExtractor extractor) {
		this.extractor = extractor;
	}

	Map.Entry<String, AbiModel> extract(File library, Path projectRoot) {
		AbiEntry entry = doExtract(library);
		String identity = entry.soname != null ? entry.soname
			: projectRoot.relativize(library.toPath()).toString();
		return new AbstractMap.SimpleImmutableEntry<>(identity, entry.model);
	}

	private AbiEntry doExtract(File library) {
		try {
			return extractor.extract(library.toPath());
		} catch (Exception ignored) {
			return new AbiEntry(null, new UnknownLibraryAbiModel(library));
		}
	}
}
