package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Link options for native linking.
 */
public interface NativeLinkOptions {
	/**
	 * Additional arguments to provide to the linker.
	 *
	 * @return a property to configure the additional arguments
	 */
	@Input
	ListProperty<String> getLinkerArgs();

	/**
	 * Additional argument providers to pass to the linker.
	 *
	 * @return a property to configure the additional argument providers
	 */
	@Nested
	ListProperty<CommandLineArgumentProvider> getLinkerArgumentProviders();
}
