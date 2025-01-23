

```
binaries {
	assert compileTask.name == 'compileDebugCpp'
	compileTask.configure {
		...
	}

	linkTask.name
	linkTask.configure { }

	createTask.configure { ... }


}
```
