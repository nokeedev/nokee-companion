# Nokee Native Companion Plugin

## Usage

### Project

The plugin [react to the `cpp-library` and `cpp-application`](link-to-section) together with [feature flags](link-to-section).

```groovy
plugins {
	id 'dev.nokee.native-companion' version '<version>'
}
```

### Settings

```groovy
plugins {
	id 'dev.nokee.native-companion' version '<version>'
}
```

### Init Script

```groovy
// Adds the plugin to the build classpath
buildscript {
	dependencies {
		classpath 'dev.nokee.native-companion:dev.nokee.native-companion.gradle.plugin:<version>'
	}
}

// Apply the plugins to all builds
gradle.beforeSettings { settings ->
	settings.apply plugin: 'dev.nokee.native-companion'
}
```

You can use the provided init script to enable the plugin globally.
This usage scenario should only be used for testing purpose.
We do not recommend using the plugin by dropping the init script inside your `~/.gradle/init.d` or your custom distribution.

## Feature

The plugin use feature flags to enable/disable functionalities.
We can enable/disable features via Gradle properties: all feature using `dev.nokee.native-companion.all-features.enabled` or individual features using `dev.nokee.native-companion.<feature-name>.enabled` (i.e. `dev.nokee.native-companion.fix-for-public-headers.enabled`).
We can also enable each features via the [`NativeCompanionExtension#enableFeaturePreview(<feature-name>)`](#TODO) Project extension.

Here's summary of all features available:

- _(disabled)_ [**native-task-object-files-extension**](#feature-native-task-object-files-extension):
- _(disabled)_ [**compile-tasks-extension**](#feature-compile-tasks-extension):
- _(disabled)_ [**native-companion.replace-cpp-compile-task**](#feature-native-companion.replace-cpp-compile-task):
- _(disabled)_ [**binary-task-extensions**](#feature-binary-task-extensions):
- _(disabled)_ [**fix-for-gradle-29492**](#feature-fix-for-gradle-29492):
- _(disabled)_ [**fix-for-gradle-29744**](#feature-fix-for-gradle-29744):
- _(disabled)_ [**fix-for-public-headers**](#feature-fix-for-public-headers):
- _(disabled)_ [**fix-headers-dependencies-for-case-insensitive**](#feature-fix-headers-dependencies-for-case-insensitive):
- _(disabled)_ [**fix-for-version-catalog**](#feature-fix-for-version-catalog):
- _(disabled)_ [**incremental-compilation-after-failure**](#feature-incremental-compilation-after-failure):

### Feature: native-task-object-files-extension

### Feature: compile-tasks-extension

### Feature: native-companion.replace-cpp-compile-task

### Feature: binary-task-extensions

### Feature: fix-for-gradle-29492

### Feature: fix-for-gradle-29744

### Feature: fix-for-public-headers

### Feature: fix-headers-dependencies-for-case-insensitive

### Feature: fix-for-version-catalog

### Feature: incremental-compilation-after-failure

