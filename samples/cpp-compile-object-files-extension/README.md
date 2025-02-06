# Exact Object Files from C++ Compilation

In very rare cases, environmental issue can cause duplicated object files.
In the wild, we have seen one case where heavy use of symbolic links caused duplicated object files to be present inside compile tasks' object file directory.
It was a combination of out-of-source build directories and project directory configuration using non-canonical base path coming from an environment variable.
Under this very specific condition, switching between two workspace where build A was a host build in one workspace and an included build in another cause the compile task to compute two distinct object file hash resulting in a duplication.
The duplicated object files caused havoc during the linking phase as Gradle's link task simply get all object files under the object file directory instead of scoping to only the produced object files.

The companion plugin decorate each native compilation task with the `objectFiles` extension, a `FileTree` that include only the object files produced by the task.

```shell {exemplar}
$ awk '/region/,/endregion/' ./app/build.gradle
		//region Example of using objectFiles extension
		tasks.register("list${name - 'main'}ObjectFiles") {
			dependsOn compileTask.get().objectFiles // it holds task dependencies
			doLast {
				println "all object files ${compileTask.get()}"
				compileTask.get().objectFiles.each {
					println " -> $it"
				}
			}
		}
		//endregion
```

List the object files:

```shell {exemplar}
$ ./gradlew listDebugObjectFiles

> Task :app:compileDebugCpp
executing compilation

> Task :app:listDebugObjectFiles
all object files task ':app:compileDebugCpp'
 -> ./app/build/obj/main/debug/f0c36b8y9kkzr0hshipmuavim/message.o
 -> ./app/build/obj/main/debug/8dasnq975rzjosysh2j5fktog/split.o
 -> ./app/build/obj/main/debug/3gfmv4hei6kdj2kxpjtavx6sh/destructor.o
 -> ./app/build/obj/main/debug/7l1rrxf4ugu1brfnb9msa2y3i/remove.o
 -> ./app/build/obj/main/debug/c2kofpfbdvl0ox5prbhyuwx8n/join.o
 -> ./app/build/obj/main/debug/57uiwkglely1v0krq64yn8ivd/add.o
 -> ./app/build/obj/main/debug/d7rpxlv4rhfra2lzuvpri9lyj/get.o
 -> ./app/build/obj/main/debug/5v08dtn7cszvzrlgdayn1y9u6/main.o
 -> ./app/build/obj/main/debug/9ryzqhmpa69of9utjiizgddi3/copy_ctor_assign.o
 -> ./app/build/obj/main/debug/60yemoco4lam131yntpgpbh2r/size.o

BUILD SUCCESSFUL
```

Note the `objectFiles` holds the expected task dependencies.

You may be thinking that we would need to rewire how we connect the object files to the other tasks.
Way ahead of you!
The native companion plugin use a technique called shadow properties.
In a nutshell, it allows to mutate read-only properties such as `CppBinary#getObjects()`.
The plugin ensure Gradle is aware of the shadow properties allowing you rest easy or to mutate the property yourself.

It's crucial to understand that you must adapt your code's awareness to the `CppBinary#objects` shadow property.
If you need to use the object files produced by a task, use the `objectFiles` extension (preferred) instead of matching the object files from the `objectFileDir` property (fallback).
