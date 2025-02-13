package dev.nokee.language.nativebase.tasks.options;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Compile options for native compilation.
 */
public interface NativeCompileOptions {
    /**
     * Additional arguments to provide to the compiler.
     *
     * @return a property to configure the additional arguments
     */
    @Input
    ListProperty<String> getCompilerArgs();

    /**
     * Additional argument providers to pass to the compiler.
     *
     * @return a property to configure the additional argument providers
     */
    @Nested
    ListProperty<CommandLineArgumentProvider> getCompilerArgumentProviders();
}
