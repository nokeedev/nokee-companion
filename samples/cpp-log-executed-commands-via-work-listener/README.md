# How to Log the C++ Compiler Commands a Build Executes

A compile task with hundreds or thousands of source files can take a long time.
While it runs, you get no feedback on its progress.
This sample shows you how to log each C++ compiler command once it completes.
Read the [work observability chapter](../../docs/manual/work-observability.adoc#work-completion-listener) to learn about work completion events.
Link tasks also support work observability, so linker commands are logged too.

## Register a Work Completion Listener

```shell {exemplar}
$ awk '/region/,/endregion/' ./build.gradle
//region Register a work completion listener
// Create the build service
abstract class LogExecutedCommands implements BuildService<BuildServiceParameters.None>, WorkCompletionListener {
	private static final Logger LOGGER = Logging.getLogger(LogExecutedCommands)

	@Override
	void onFinish(WorkCompletionListener.Event event) {
		LOGGER.lifecycle(([event.workItem.executable] + event.workItem.arguments).join(' '))
	}
}

// Register the build service under the well-known service name
gradle.sharedServices.registerIfAbsent(WorkCompletionListener.SERVICE_NAME, LogExecutedCommands) {}
//endregion
```

## Build the Application

Notice each command is logged under its task as soon as it completes:

```shell {exemplar}
$ ./gradlew assemble --console=verbose
> Task :compileDebugCpp
/usr/bin/clang++ @./build/tmp/compileDebugCpp/options.txt ./src/main/cpp/greeting.cpp -o ./build/obj/main/debug/cvwzeuyq45yhnj6dfp5uv13bc/greeting.o
/usr/bin/clang++ @./build/tmp/compileDebugCpp/options.txt ./src/main/cpp/main.cpp -o ./build/obj/main/debug/5v08dtn7cszvzrlgdayn1y9u6/main.o

> Task :linkDebug
/usr/bin/clang++ @./build/tmp/linkDebug/options.txt

> Task :installDebug
> Task :assemble

BUILD SUCCESSFUL
```

## Build Again After a Change

Notice only the executed commands are logged, here the compilation of the changed `greeting.cpp` and the link:

```shell {exemplar}
$ patch --input=change-greeting.patch
patching file 'src/main/cpp/greeting.cpp'
$ ./gradlew assemble --console=verbose
> Task :compileDebugCpp
/usr/bin/clang++ @./build/tmp/compileDebugCpp/options.txt ./src/main/cpp/greeting.cpp -o ./build/obj/main/debug/cvwzeuyq45yhnj6dfp5uv13bc/greeting.o

> Task :linkDebug
/usr/bin/clang++ @./build/tmp/linkDebug/options.txt

> Task :installDebug
> Task :assemble

BUILD SUCCESSFUL
```
