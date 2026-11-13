# How to Record and Replay the Commands of a C++ Build

Some tools run the compiler and linker commands of a build on their own, outside of Gradle.
This sample shows you how to record those commands from the [work plans](../../docs/manual/work-observability.adoc#work-plan) of a build, then replay them.

The [`record.init.gradle`](record.init.gradle) init script holds the recording logic.
When the `record` property is present, it writes the commands of each task to `build/commands.txt`, one command per line, before the task executes.

## Record the Commands

Build the application with the init script and the `record` property:

```shell {exemplar}
$ ./gradlew assemble --init-script record.init.gradle -Precord --console=verbose
> Task :compileDebugCpp
> Task :linkDebug
> Task :installDebug
> Task :assemble

BUILD SUCCESSFUL
$ ./build/exe/main/debug/app
Hello, World!
```

## Replay After a Change

Change the greeting, then replay the recorded commands without Gradle:

```shell {exemplar}
$ patch --input=change-greeting.patch
patching file 'src/main/cpp/greeting.cpp'
$ cat ./build/commands.txt | xargs -L 1 env
$ ./build/exe/main/debug/app
Hello, Replay!
```
