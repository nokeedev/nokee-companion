
```shell
$ ./gradlew help
```

```
components.withType(CppExecutable) {
	assert compileTask.name in ['compileDebugCpp', 'compileReleaseCpp']
	compileTask.configure { /*...*/ }

	assert linkTask.name in ['linkDebug', 'linkRelease']
	linkTask.configure { /*...*/ }

	assert installTask.name in ['installDebug', 'installRelease']
	installTask.configure { /*...*/ }
}

components.withType(CppStaticLibrary) {
	assert compileTask.name in ['compileDebugCpp', 'compileReleaseCpp']
	compileTask.configure { /*...*/ }

	assert createTask.name in ['createDebug', 'createRelease']
	createTask.configure { /*...*/ }
}
```

We are essentially replacing the return type of the task getter from `Provider` to `TaskProvider`.
It allows a more lazy way to configure tasks in the Groovy DSL build script.
