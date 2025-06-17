# C++ Overlinking Avoidance

The Gradle core plugins wrongly carry the link dependencies transitively for shared libraries.
In reality, a shared library is completely linked hence does not require passing further link libraries.
On top of that, it leads to a problem called overlinking causing unnecessary libraries to be present in the NEEDED table.

## Project Structure

- app (Exe) -> bar (shared)
- bar (shared) -> foo (shared)
- bar (shared) -> foobar (static)
- foobar (static) -> far (shared)

## Environment

We can better demonstrate this sample under a Linux environment (either GCC/Clang).
For this reason, we recommend using the provided docker container:

```shell
$ docker run --platform linux/amd64 -it --rm -v "$(pwd):/workspace" -w /workspace $(docker build --platform linux/amd64 -q .devcontainer)
```

## Overlinking by Default

```shell
$ ./gradlew :app:installRelease

BUILD SUCCESSFUL
```

<details>
<summary>Linux</summary>

```shell
$ readelf -d app/build/exe/main/release/app | grep NEEDED
 0x0000000000000001 (NEEDED)             Shared library: [libbar.so]
 0x0000000000000001 (NEEDED)             Shared library: [libfoo.so]
 0x0000000000000001 (NEEDED)             Shared library: [libfar.so]
$ cat app/build/tmp/linkRelease/options.txt | grep -E '\.so|\.a'
./bar/build/lib/main/release/stripped/libbar.so
./foo/build/lib/main/release/stripped/libfoo.so
./foobar/build/lib/main/release/libfoobar.a
./far/build/lib/main/release/stripped/libfar.so
```
</details>

<details>
<summary>macOS</summary>

```shell
$ otool -L app/build/exe/main/release/app
app/build/exe/main/release/app:
        ./bar/build/lib/main/release/libbar.dylib (compatibility version 0.0.0, current version 0.0.0)
        ./foo/build/lib/main/release/libfoo.dylib (compatibility version 0.0.0, current version 0.0.0)
        ./far/build/lib/main/release/libfar.dylib (compatibility version 0.0.0, current version 0.0.0)
$ cat app/build/tmp/linkRelease/options.txt | grep -E '\.dylib|\.a'
./bar/build/lib/main/release/stripped/libbar.dylib
./foo/build/lib/main/release/stripped/libfoo.dylib
./foobar/build/lib/main/release/libfoobar.a
./far/build/lib/main/release/stripped/libfar.dylib
```
</details>

## Avoidance

```shell
$ ./gradlew :app:installRelease -Pdev.nokee.native-companion.overlinking-avoidance.enabled=true

BUILD SUCCESSFUL
```

<details>
<summary>Linux</summary>

```shell
$ readelf -d app/build/exe/main/release/app | grep NEEDED
 0x0000000000000001 (NEEDED)             Shared library: [libbar.so]
$ cat app/build/tmp/linkRelease/options.txt | grep -E '\.so|\.a'
./bar/build/lib/main/release/stripped/libbar.so
```

Notice no NEEDED entry for `libfoo.so` or `libfar.so` and we don't link against libfoobar.a

```shell
$ cat app/build/tmp/linkRelease/options.txt | grep rpath-link
-Wl,-rpath-link=../foo/build/lib/main/release/stripped
-Wl,-rpath-link=../far/build/lib/main/release/stripped
```

Notice we only care about shared library during rpath-link.

> Under Linux, Clang will have the same result.
> Try it out using `-DuseClang` flag to the Gradle command line.

</details>

<details>
<summary>macOS</summary>

```shell
$ otool -L app/build/exe/main/release/app
app/build/exe/main/release/app:
        ./bar/build/lib/main/release/libbar.dylib (compatibility version 0.0.0, current version 0.0.0)
$ cat app/build/tmp/linkRelease/options.txt | grep -E '\.dylib|\.a'
./bar/build/lib/main/release/stripped/libbar.dylib
```
</details>
