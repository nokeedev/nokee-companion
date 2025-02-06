# C++ Multiple Public Header Directories

Allow configuring multiple headers:

```shell {exemplar}
$ awk '/region/,/endregion/' ./message/build.gradle
//region Configure single public header directory
library {
	publicHeaders.from('../includes/message')
}
//endregion
$ awk '/region/,/endregion/' ./utilities/build.gradle
//region Configure multiple public header directories
library {
	publicHeaders.from('../includes/list')
	publicHeaders.from('../includes/utilities')
}
//endregion
```

```shell {exemplar}
$ ./gradlew :app:assemble
> Task :utilities:syncPublicHeaders
> Task :message:compileDebugCpp
> Task :app:compileDebugCpp
> Task :utilities:compileDebugCpp
> Task :app:assemble

BUILD SUCCESSFUL
```



```shell {exemplar}
$ ./gradlew clean publishAllPublicationsToMavenRepository

BUILD SUCCESSFUL
$ zipinfo -Z2 ./repo/nokee-samples/message/1.0/message-1.0-cpp-api-headers.zip
message.h
$ zipinfo -Z2 ./repo/nokee-samples/utilities/1.0/utilities-1.0-cpp-api-headers.zip | sort
linked_list.h
string_utils.h
```


