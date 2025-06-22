# Demonstrate how to fix gradle/gradle#29492

The supplied plugin will remap the `CppBinary#getCppSource()` to the `CppBinary#getCompileTask()` as a standard `FileCollection` input.
We loose on the caching but gain on the correctness.
The performance impact should be minimal in most cases.

Ensure up-to-date:

```shell {exemplar}
$ ./gradlew assemble

BUILD SUCCESSFUL
$ ./gradlew assemble

BUILD SUCCESSFUL
19 actionable tasks: 19 up-to-date
```

Let's change a file's relative path by moving it to a subdirectory.
Note that we carefully crafted the source file to explicitly include a header side-by-side to the compilation unit.
Moving the compilation unit will cause a compilation failure as the header will no longer be discoverable.

```shell {exemplar}
$ mv app/src/main/cpp/main.cpp app/src/main/cpp/dir/main.cpp
$ ./gradlew assemble -i

> Task :app:compileDebugCpp FAILED
Task ':app:compileDebugCpp' is not up-to-date because:
  Input property 'source' file ./app/src/main/cpp/main.cpp has been removed.
  Input property 'source' file ./app/src/main/cpp/dir/main.cpp has been added.
See file://./app/build/tmp/compileDebugCpp/output.txt for all output for compileDebugCpp.
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/dir/main.cpp -o ./app/build/obj/main/debug/buismfjhii726atfvl2k8agi2/main.o
./app/src/main/cpp/dir/main.cpp:8:10: fatal error: 'message.h' file not found
    8 | #include "message.h"
      |          ^~~~~~~~~~~
1 error generated.

BUILD FAILED
```

The build fails as expected.
Notice the compile task correctly identified `main.cpp` being removed from `src/main/cpp` and added to the subdirectory `dir`.
This is the correct behaviour.
