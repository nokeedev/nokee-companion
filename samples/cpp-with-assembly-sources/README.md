# C++ with Assembly Sources

This sample show how to build an application with a mixed language library (i.e. C++ and assembly).

```shell {exemplar}
$ ./gradlew installRelease

BUILD SUCCESSFUL
$ ./app/build/install/main/release/app
Hello, World!
12
```

The sample use an approximate implementation of an assembly language plugin that integrates with the C++ plugins.
It wires an additional compile task to the C++ binaries which will automatically wire its object files to the appropriate link tasks.
