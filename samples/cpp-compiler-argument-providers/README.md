# C++ Compiler Argument Providers

```shell
$ docker build .
$ ./verify loc-a

BUILD SUCCESSFUL
$ ./verify loc-b

> Task :compileDebugCpp FROM-CACHE

BUILD SUCCESSFUL
```

```shell
$ echo "// more data" >> my-forced.h
$ ./verify loc-b

> Task :compileDebugCpp

BUILD SUCCESSFUL
$ ./verify loc-a

> Task :compileDebugCpp FROM-CACHE

BUILD SUCCESSFUL
```

Use docker to show caching with different path.

WARNING: Just like normal compilerArgs, anything define does not participate in the header discovery.
