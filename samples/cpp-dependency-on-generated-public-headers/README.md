# Generate headers

have dependency on library that generate its own headers


// TODO: Distinction from shadowing sourceFiles and public header files for generator task like
//  protobuf generator without losing the convention

```shell {exemplar}
$ ./gradlew :app:assemble

BUILD SUCCESSFUL
```


```shell {exemplar}
$ ./gradlew clean :lib:publishAllPublicationsToMavenRepository

BUILD SUCCESSFUL
$ zipinfo -Z2 ./repo/nokee-samples/lib/1.1/lib-1.1-cpp-api-headers.zip | sort
linked_list.h
message.h
string_utils.h
```
