# C++ Unit Test's Tested Component Integration

This sample shows `cpp-unit-test` plugin can integrate with different test element of the tested component.
The `native-companion` plugin rewire the integration between `cpp-unit-test`'s test suite and its tested component as a normal dependency.
Selecting the right test elements to use becomes as simple as modifying the dependency.

By default, the test suite will link against the testable objects of the main component (same as before).
Using the `testedComponent(...)` dependency modifier, we can select a different test element.

## Source Elements

The test suite will compile the tested component's sources together with its own sources.
In this scenario, we have full control over the compilation phase.
Note the test suite receive all the main component's headers (public and private) and those normally pulled from its implementation dependencies.

```shell {exemplar}
$ awk '/region/,/endregion/' ./list/build.gradle
//region Compile against the sources element
unitTest {
	dependencies {
		implementation testedComponent(project).asSources()
	}
}
//endregion
```

## Product Element

The test suite will link against the tested component's product (static or shared library).
This integration is not available for application components.
Note the test suite receive all the main component's headers (public and private) and those normally pulled from its implementation dependencies.

```shell {exemplar}
$ awk '/region/,/endregion/' ./utils/build.gradle
//region Link against the product element
unitTest {
	dependencies {
		implementation testedComponent(project).asProduct()
	}
}
//endregion
```

## Remote Product

The test suite will link against the tested component's product (static or shared library) like any normal dependencies.
The important distinction is the test suite will have only access to the tested component's public headers and those normally pulled from it's api dependencies.

```shell {exemplar}
$ awk '/region/,/endregion/' ./message-test/build.gradle
//region Link against another project's product
unitTest {
	dependencies {
		// tested component as normal project dependency
		//   using testedComponent(...) serve as a way to mark the dependency
		implementation testedComponent(project(':message'))
	}
}
//endregion
```

## Demonstration

Executing the tests via `runTest` task:

```shell {exemplar}
$ ./gradlew runTest

BUILD SUCCESSFUL
```
