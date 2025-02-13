package dev.nokee.commons.gradle.tasks.options;

import org.gradle.api.Action;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Nested;

import java.io.File;

public interface SourceOptionsAware<T> {
	SourceOptionsAware<T> source(Object sourcePath, Action<? super T> configureAction);

	Options<T> getOptions();

	interface Options<T> {
		Provider<T> forSource(File file);
		Provider<Iterable<SourceOptions<T>>> forAllSources();

		@Nested
		SetProperty<SourceBucket> getBuckets();
	}
}
