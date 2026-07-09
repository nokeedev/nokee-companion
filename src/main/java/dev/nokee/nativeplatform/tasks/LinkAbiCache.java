package dev.nokee.nativeplatform.tasks;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import javax.inject.Inject;
import java.io.File;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

abstract /*final*/ class LinkAbiCache implements BuildService<BuildServiceParameters.None> {
	private final Map<File, MapEntry> cache = new ConcurrentHashMap<>();

	@Inject
	public LinkAbiCache() {}

	public Object find(Path path, Callable<Object> mapper) {
		return cache.computeIfAbsent(path.toFile(), MapEntry::new).get(mapper);
	}

	private static class MapEntry {
		private final File path;
		private CachedAbiEntry ref;

		public MapEntry(File path) {
			this.path = path;
		}

		synchronized Object get(Callable<Object> mapper) {
			try {
				BasicFileAttributes attributes = Files.getFileAttributeView(path.toPath(), BasicFileAttributeView.class).readAttributes();

				if (ref == null) {
					Object result = mapper.call();
					ref = new CachedAbiEntry(attributes.size(), attributes.lastModifiedTime().toMillis(), result);
					return result;
				}

				if (attributes.size() == ref.size && attributes.lastModifiedTime().toMillis() == ref.modtime) {
					Object result = ref.get();
					if (result == null) {
						result = mapper.call();
						ref = new CachedAbiEntry(attributes.size(), attributes.lastModifiedTime().toMillis(), result);
						return result;
					}
					return result;
				}

				Object result = mapper.call();
				ref = new CachedAbiEntry(attributes.size(), attributes.lastModifiedTime().toMillis(), result);
				return result;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static final class CachedAbiEntry extends SoftReference<Object> {
		private final long size;
		private final long modtime;

		public CachedAbiEntry(long size, long modtime, Object entry) {
			super(entry);
			this.size = size;
			this.modtime = modtime;
		}
	}
}
