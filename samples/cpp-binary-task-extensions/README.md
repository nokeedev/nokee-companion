# C++ Binary Task Extensions

Build authors have to resolve the binary tasks if they want to configure them.
They can avoid them, but it requires a conscious and constant effort.
We introduced `CppNames` (in `commons-names`) to help with the task.
However, the DSL should simply expose `TaskProvider` which is the reason for this feature.

We configured this project to show how build author normally interact with binary tasks.

```shell {exemplar}
$ ./gradlew assemble

BUILD SUCCESSFUL
$ awk '/region/,/endregion/' ./app/build.gradle
	//region Just for demonstration purpose
	binaries.configureEach {
		compileTask.get().compilerArgs.addAll([/*...*/])
		linkTask.get().linkerArgs.addAll([/*...*/])
		installTask.get().lib([/*...*/])
	}
	//endregion
$ awk '/region/,/endregion/' ./lib/build.gradle
	//region Just for demonstration purpose
	binaries.configureEach(CppSharedLibrary) {
		compileTask.get().compilerArgs.addAll([/*...*/])
		linkTask.get().linkerArgs.addAll([/*...*/])
	}
	binaries.configureEach(CppStaticLibrary) {
		compileTask.get().compilerArgs.addAll([/*...*/])
		createTask.get().staticLibArgs.addAll([/*...*/])
	}
	//endregion
```

When using the [native-companion plugin](TODO):

```shell {exemplar}
$ patch < use-native-companion.patch
patching file 'app/build.gradle'
patching file gradle.properties
patching file 'lib/build.gradle'
patching file settings.gradle
```

We can treat the task getter as returning `TaskProvider` and configure those tasks without realizing them:

```shell {exemplar}
$ ./gradlew assemble

BUILD SUCCESSFUL
$ awk '/region/,/endregion/' ./app/build.gradle
	//region Just for demonstration purpose
	binaries.configureEach {
		compileTask { compilerArgs.addAll([/*...*/]) }
		linkTask { linkerArgs.addAll([/*...*/]) }
		installTask { lib([/*...*/]) }
	}
	//endregion
$ awk '/region/,/endregion/' ./lib/build.gradle
	//region Just for demonstration purpose
	binaries.configureEach(CppSharedLibrary) {
		compileTask { compilerArgs.addAll([/*...*/]) }
		linkTask { linkerArgs.addAll([/*...*/]) }
	}
	binaries.configureEach(CppStaticLibrary) {
		compileTask { compilerArgs.addAll([/*...*/]) }
		createTask { staticLibArgs.addAll([/*...*/]) }
	}
	//endregion
```

We are essentially replacing the return type of the task getter from `Provider` to `TaskProvider`.
It allows a more lazy way to configure tasks in the Groovy DSL build script.
