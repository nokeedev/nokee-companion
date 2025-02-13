# C++ Additional Publishing Attributes

The base publication mechanic doesn't allow for additional attribute on publications.
We need to use `dev.nokee.multiplatform-publishing` plugin.
Integration is done automatically using the `multiplatform-publishing` feature.

```shell {exemplar}
$ ./gradlew :app:outgoingVariants :lib:outgoingVariants

> Task :app:outgoingVariants

--------------------------------------------------
Variant debugRuntimeElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

--------------------------------------------------
Variant releaseRuntimeElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

> Task :lib:outgoingVariants
--------------------------------------------------
Variant cppApiElements
--------------------------------------------------

Capabilities
    - native-samples:lib:1.2 (default capability)
Attributes
    - artifactType     = directory
    - org.gradle.usage = cplusplus-api
Artifacts
    - src/main/public

Secondary Variants (*)

    --------------------------------------------------
    Secondary Variant compress-headers
    --------------------------------------------------

    Attributes
        - artifactType     = zip
        - org.gradle.usage = cplusplus-api
    Artifacts
        - build/headers/cpp-api-headers.zip (artifactType = zip, classifier = cpp-api-headers)

--------------------------------------------------
Variant debugLinkElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

--------------------------------------------------
Variant debugRuntimeElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

--------------------------------------------------
Variant releaseLinkElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

--------------------------------------------------
Variant releaseRuntimeElements (i)
--------------------------------------------------

Attributes
    - native-samples.built-with-toolchain = clang

BUILD SUCCESSFUL
$ ./gradlew --console=verbose :lib:publish

BUILD SUCCESSFUL
$ ./gradlew --console=verbose :app:publish
```

Notice Gradle skips all `*Main*Publication*` tasks.
There are the core publishing tasks which doesn't allow for additional attributes.
The `dev.nokee.multiplatform-publishing` is now responsible for the publishing and allows users to add additional attributes just like you would normally do using Gradle's public APIs:

```shell {exemplar}
$ cat gradle/plugins/src/main/groovy/nokee-plugins.toolchain-attributes.gradle
import static dev.nokee.commons.names.CppNames.*

// Configure toolchain attribute on outgoingVariants
def BUILT_WITH_TOOLCHAIN_ATTRIBUTE = Attribute.of('native-samples.built-with-toolchain', String)
components.withType(CppLibrary.class).configureEach { component ->
	component.binaries.configureEach(CppBinary) { binary ->
		// Modifying ConfigurableComponentWithLinkUsage#getLinkAttributes() has no effect on the published artifacts.
		//   We must rely on multiplatform-publishing instead.
		project.getConfigurations().named(linkElementsConfigurationName(binary)).configure {
			attributes {
				attribute(BUILT_WITH_TOOLCHAIN_ATTRIBUTE, binary.toolChain.name)
			}
		}
	}
}
project.components.withType(CppBinary.class).configureEach { binary ->
	if (!(binary instanceof TestComponent)) {
		// Modifying ConfigurableComponentWithRuntimeUsage#getRuntimeAttributes() has no effect on the published artifacts.
		//   We must rely on multiplatform-publishing instead.
		project.getConfigurations().named(runtimeElementsConfigurationName(binary)).configure {
			attributes {
				attribute(BUILT_WITH_TOOLCHAIN_ATTRIBUTE, binary.toolChain.name)
			}
		}
	}
}
```


