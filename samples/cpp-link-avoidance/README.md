# C++ Link Avoidance

When a shared library is rebuilt, Gradle re-links all consumers by default — even when the library's exported ABI (function names and signatures) has not changed.
Link avoidance eliminates unnecessary re-links by comparing the library's extracted ABI snapshot before and after a rebuild.
If the ABI is identical, the consumer's link task is skipped as `UP-TO-DATE`.

## Project Structure

- `app` (executable) → `lib` (shared library)

`lib` exposes a single function `greet()` that returns an integer.
`app` links against `lib` and invokes `greet()`.

## Initial Build

```shell {exemplar}
$ ./gradlew :app:assemble
$ ./gradlew :app:assemble --console=verbose
> Task :lib:compileDebugCpp UP-TO-DATE
> Task :lib:linkDebug UP-TO-DATE
> Task :app:compileDebugCpp UP-TO-DATE
> Task :app:linkDebug UP-TO-DATE

BUILD SUCCESSFUL
```

## Implementation-Only Change (Link Avoidance Kicks In)

We change the return value of `greet()` without altering its signature.
This modifies the library binary but leaves the exported ABI unchanged.

```shell {exemplar}
$ patch --input=change-implementation.patch
patching file 'lib/src/main/cpp/greeter.cpp'
```

Rebuild everything. Because the ABI of `lib` has not changed, `app`'s link step is skipped:

```shell {exemplar}
$ ./gradlew :app:assemble --console=verbose

> Task :lib:compileDebugCpp
> Task :lib:linkDebug
> Task :app:compileDebugCpp UP-TO-DATE
> Task :app:linkDebug UP-TO-DATE

BUILD SUCCESSFUL
```

Notice `:app:linkDebug` is `UP-TO-DATE` — no re-link occurred even though `lib` was rebuilt.

## ABI Change (Re-link Required)

We add a new exported function `farewell()` to `lib`, which changes its ABI.

```shell {exemplar}
$ patch --input=add-exported-symbol.patch
patching file 'lib/src/main/cpp/additional.cpp'
```

Rebuild everything. The ABI changed (new exported symbol), so `app` must be re-linked:

```shell {exemplar}
$ ./gradlew :app:assemble --console=verbose

> Task :lib:compileDebugCpp
> Task :lib:linkDebug
> Task :app:compileDebugCpp UP-TO-DATE
> Task :app:linkDebug

BUILD SUCCESSFUL
```

Notice `:app:linkDebug` executed this time — the new symbol required a fresh link.
