# C++ Additional Publishing Variants

This sample demonstrate how to include additional variants to C++ publications, mainly the bridge publication.
For the purpose of this demonstration, we use a crud approximate implementation of a Java Native Interface (JNI) library support.
This JNI support is meant for demonstration purpose only, the focus is on the additional variants to C++ publications.

The goal is to add a `java-runtime` variant to the C++ bridge publication that carries the Java code portion of the JNI library.
Gradle has internal support to achieve this work, but using internal APIs is frown upon.
To use public APIs (aka vanilla `AdhocComponentWithVariants`), we will use the `dev.nokee.multiplatform-publishing` plugin.
The `multiplatform-publishing` feature takes care of the configuration of the plugin.
For native project using `cpp-library` and `cpp-applicate`, we named the bridge `AdhocComponentWithVariants` component `cpp`.
Hence, adding a new variant is as simple as configuring your `ConsumableConfiguration` and adding it to the component:

```shell {exemplar}
$ awk '/region/,/endregion/' ./lib/build.gradle
//region Add JNI runtime elements to C++ bridge component
components.cpp.addVariantsFromConfiguration(configurations.getByName(sourceSets.jni.runtimeElementsConfigurationName)) {}
//endregion
```

Wow! So simple. But does it work? Let's see.
First we need to publish the library:

```shell {exemplar}
$ ./gradlew :lib:publish

BUILD SUCCESSFUL
```

Then, we can run the Java application:

```shell {exemplar}
$ ./gradlew :app:run

> Task :app:run
Bonjour, World!

BUILD SUCCESSFUL
```

Note that we could have also introduced a remote variant as a separate platform publication.
Both approach works.
The decision factor falls back on the publication strategy chosen for the project.
