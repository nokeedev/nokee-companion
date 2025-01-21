package dev.nokee.legacy;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;

import javax.inject.Inject;
import java.util.function.Consumer;

abstract class GradleIssue29492Fix {
	@Inject
	public GradleIssue29492Fix() {}

	public abstract ConfigurableFileCollection getSource();

	public void source(Object sourceFiles) {
		getSource().from(sourceFiles);
	}

	public GradleIssue29492Fix attachSource(Consumer<? super FileCollection> action) {
		action.accept(getSource());
		return this;
	}
}
