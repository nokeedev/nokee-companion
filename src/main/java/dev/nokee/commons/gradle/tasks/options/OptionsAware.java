package dev.nokee.commons.gradle.tasks.options;

import org.gradle.api.tasks.Nested;

public interface OptionsAware {
	@Nested
	Options getOptions();

	interface Options {}
}
