# How to Generate a Compilation Database for a C++ Build

Tools such as clangd read a `compile_commands.json` compilation database to find the compiler arguments of each source file.
This sample shows you how to generate it from the [work plan](../../docs/manual/work-observability.adoc#work-plan) of a compile task.
The sample expects a GCC or Clang compiler, where `-o` names the object file.

## Generate the Compilation Database

Look at the [`nokee-samples.compilation-database` plugin](gradle/plugins/src/main/groovy/nokee-samples.compilation-database.gradle) for the implementation of the `compilationDatabase` task.

Notice only `:compilationDatabase` runs, so nothing is compiled:

```shell {exemplar}
$ ./gradlew compilationDatabase --console=verbose
> Task :compilationDatabase

BUILD SUCCESSFUL
$ cat compile_commands.json
[
    {
        "directory": "./build/obj/main/debug",
        "file": "./src/main/cpp/greeting.cpp",
        "output": "./build/obj/main/debug/cvwzeuyq45yhnj6dfp5uv13bc/greeting.o",
        "arguments": [
            "/usr/bin/clang++",
            "-x",
            "c++",
            "-c",
            "-g",
            "-I",
            "./src/main/headers",
            [...]
            "./src/main/cpp/greeting.cpp",
            "-o",
            "./build/obj/main/debug/cvwzeuyq45yhnj6dfp5uv13bc/greeting.o"
        ]
    },
    {
        "directory": "./build/obj/main/debug",
        "file": "./src/main/cpp/main.cpp",
        "output": "./build/obj/main/debug/5v08dtn7cszvzrlgdayn1y9u6/main.o",
        "arguments": [
            "/usr/bin/clang++",
            "-x",
            "c++",
            "-c",
            "-g",
            "-I",
            "./src/main/headers",
            [...]
            "./src/main/cpp/main.cpp",
            "-o",
            "./build/obj/main/debug/5v08dtn7cszvzrlgdayn1y9u6/main.o"
        ]
    }
]
```
