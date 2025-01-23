

Show that you can have multiple compile task (duplicate current compile task with a new one) and the object files of both gets added
to the link exe, link shared and create tasks as well as included in the test suite.

Showcase the compileTasks extensions and derive compile task

```
binaries {
  compileTasks.configureEach {
	compilerArgs.add('...')
  }
}
```

Mention that all objects files are shadowed onto the CppBinary#objects
