# Incremental C++ Compilation After Failure

This sample demonstrate incremental C++ compilation after a failure.
Gradle makes no distinction between a controlled failure and a catastrophic failure of a task.
Hence, Gradle doesn't track the state of a task after it fails.
For incremental task, such as the compile task, any change to the task output will result in a full rebuild.
Unfortunately, a compilation failure generally goes hand-in-hand with changing some of the task outputs.
On the next invocation, after fixing the compilation failure, Gradle force the task to perform a full rebuild.
In real-life scenarios, this behavior is quite damaging to the _build-code-build_ development cycle as it can force recompilation of several thousands of files.
The native companion solve this problem for the core `cpp-application` and `cpp-library` plugins.

First, let's make sure our code is up-to-date:

```shell {exemplar}
$ ./gradlew assemble

BUILD SUCCESSFUL
$ ./gradlew assemble

BUILD SUCCESSFUL
7 actionable tasks: 0 executed, 7 up-to-date
```

Notice that all tasks are up-to-date.
Suppose we want to fix the compilation warning in two files:

```shell {exemplar}
$ patch --input=introduce-compilation-failure.patch
patching file 'app/src/main/cpp/a-source-with-warnings.cpp'
patching file 'app/src/main/cpp/another-source-with-warnings.cpp'
```

However, our change inadvertently introduce a compilation failure:

```shell {exemplar}
$ ./gradlew assemble -i

> Task :app:compileDebugCpp FAILED
Task ':app:compileDebugCpp' is not up-to-date because:
  Input property 'source' file ./app/src/main/cpp/a-source-with-warnings.cpp has changed.
  Input property 'source' file ./app/src/main/cpp/another-source-with-warnings.cpp has changed.
See file://./app/build/tmp/compileDebugCpp/output.txt for all output for compileDebugCpp.
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/another-source-with-warnings.cpp -o ./app/build/obj/main/debug/bf8jm0n2b8vc37bvbcxj7osrh/another-source-with-warnings.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/a-source-with-warnings.cpp -o ./app/build/obj/main/debug/9xgvrlxlkixzbtr630iark821/a-source-with-warnings.o
./app/src/main/cpp/a-source-with-warnings.cpp:4:11: error: expected ';' after return statement
    4 |         return 42  // missing semi-colon expected to simulate compilation failure
      |                  ^
      |                  ;
1 error generated.

BUILD FAILED
```

It happened to all of us.
We see the issue right away and perform the fix.

```shell {exemplar}
$ patch --input=fix-compilation-failure.patch
patching file 'app/src/main/cpp/a-source-with-warnings.cpp'
```

In our little scenario, one file got compiled which should have changed the output.
Thanks to a little trick, when we recompile only the changed files are recompile, successfully performing an incremental build after a controlled task failure.

```shell {exemplar}
$ ./gradlew assemble -i

> Task :app:compileDebugCpp
Task ':app:compileDebugCpp' is not up-to-date because:
  Input property 'source' file ./app/src/main/cpp/a-source-with-warnings.cpp has changed.
  Input property 'source' file ./app/src/main/cpp/another-source-with-warnings.cpp has changed.
See file://./app/build/tmp/compileDebugCpp/output.txt for all output for compileDebugCpp.
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/another-source-with-warnings.cpp -o ./app/build/obj/main/debug/bf8jm0n2b8vc37bvbcxj7osrh/another-source-with-warnings.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/a-source-with-warnings.cpp -o ./app/build/obj/main/debug/9xgvrlxlkixzbtr630iark821/a-source-with-warnings.o

BUILD SUCCESSFUL
```
