package dev.nokee.commons.gradle.tasks.options;

import org.gradle.api.Action;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

public interface SourceOptions<T> {
	Provider<RegularFile> getSourceFile();
	T getOptions();
	void options(Action<? super T> configureAction);
}
