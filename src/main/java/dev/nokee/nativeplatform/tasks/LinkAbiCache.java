package dev.nokee.nativeplatform.tasks;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

abstract /*final*/ class LinkAbiCache implements BuildService<BuildServiceParameters.None> {
	private final Map<String, AbiEntry> cache = new ConcurrentHashMap<>();

	@Inject
	public LinkAbiCache() {}

	public AbiEntry find(String hash, Callable<AbiEntry> mapper) {
		return cache.computeIfAbsent(hash, __ -> {
			try {
				return mapper.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}
}
