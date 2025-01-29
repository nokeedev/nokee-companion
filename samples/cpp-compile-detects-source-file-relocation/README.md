# Demonstrate how to fix gradle/gradle#29492

The supplied plugin will remap the `CppBinary#getCppSource()` to the `CppBinary#getCompileTask()` as a standard `FileCollection` input.
We loose on the caching but gain on the correctness.
The performance impact should be minimal in most cases.

```shell {exemplar}
$ ./gradlew assemble
```
