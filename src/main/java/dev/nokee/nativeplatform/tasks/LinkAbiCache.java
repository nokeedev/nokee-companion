package dev.nokee.nativeplatform.tasks;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

abstract /*final*/ class LinkAbiCache implements BuildService<BuildServiceParameters.None> {
	private final Map<File, CachedAbiEntry> cache = new ConcurrentHashMap<>();

	@Inject
	public LinkAbiCache() {}

	public AbiEntry find(Path path, Callable<AbiEntry> mapper) {
		return cache.compute(path.toFile(), (File f, CachedAbiEntry value) -> {
			try {
				BasicFileAttributes attributes = Files.getFileAttributeView(f.toPath(), BasicFileAttributeView.class).readAttributes();

				if (value == null) {
					return new CachedAbiEntry(attributes.size(), attributes.lastModifiedTime().toMillis(), mapper.call());
				}

				if (attributes.size() == value.size && attributes.lastModifiedTime().toMillis() == value.modtime) {
					return value;
				}

				return new CachedAbiEntry(attributes.size(), attributes.lastModifiedTime().toMillis(), mapper.call());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).entry;
	}

	private static final class CachedAbiEntry {
		private final long size;
		private final long modtime;
		private final AbiEntry entry;

		public CachedAbiEntry(long size, long modtime, AbiEntry entry) {
			this.size = size;
			this.modtime = modtime;
			this.entry = entry;
		}
	}
}
