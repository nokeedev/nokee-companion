package dev.nokee.nativeplatform.tasks;

import org.gradle.api.provider.Provider;

interface ExportedSymbol {
	Provider<String> getName();
}
