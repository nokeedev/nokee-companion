# C++ Compile Options per Source Files

In native, it's often necessary to declare compile flags per-source files.
One such scenario arise when enabling stricter compilation warnings by treating them all as errors.
It becomes counterproductive to relax a warning class for all sources.
Instead, we would prefer relaxing the warning class only for the affected files.

This sample shows how to use per-source compile options through the compile task:

```shell {exemplar}
$ awk '/region/,/endregion/' ./app/build.gradle
	//region Different ways to configure per-source options...
	binaries.configureEach {
		// ...via file tree
		compileTask.get().source(fileTree('src/main/cpp') { include '*-warnings.cpp' }) {
			compilerArgs.add('-Wno-error=constant-conversion')
		}
		// ...or for a specific file
		compileTask.get().source('src/main/cpp/another.cpp') {
			compilerArgs.add('-DUSE_NO_WARNING_CODE')
		}
	}
	//endregion
$ awk '/region/,/endregion/' ./lib/build.gradle
	//region Disable warning for specific source file
	binaries.configureEach {
		compileTask.get().compilerArgs.add('-Wno-error=reorder-ctor')

		compileTask.get().source('src/main/cpp/lib.cpp') {
			compilerArgs.add('-Wno-error=constant-conversion')
		}
	}
	//endregion
```

Let's assemble the application:

```shell {exemplar}
$ ./gradlew assemble -i

> Task :lib:compileDebugCpp
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/destructor.cpp -o ./lib/build/obj/main/debug/3gfmv4hei6kdj2kxpjtavx6sh/destructor.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/split.cpp -o ./lib/build/obj/main/debug/8dasnq975rzjosysh2j5fktog/split.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/remove.cpp -o ./lib/build/obj/main/debug/7l1rrxf4ugu1brfnb9msa2y3i/remove.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/add.cpp -o ./lib/build/obj/main/debug/57uiwkglely1v0krq64yn8ivd/add.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/join.cpp -o ./lib/build/obj/main/debug/c2kofpfbdvl0ox5prbhyuwx8n/join.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/get.cpp -o ./lib/build/obj/main/debug/d7rpxlv4rhfra2lzuvpri9lyj/get.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/copy_ctor_assign.cpp -o ./lib/build/obj/main/debug/9ryzqhmpa69of9utjiizgddi3/copy_ctor_assign.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/options.txt ./lib/src/main/cpp/size.cpp -o ./lib/build/obj/main/debug/60yemoco4lam131yntpgpbh2r/size.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./lib/build/obj/main/debug Command: /usr/bin/clang++ @./lib/build/tmp/compileDebugCpp/ebel2vnfw5438u8fmr181tbvm/options.txt ./lib/src/main/cpp/lib.cpp -o ./lib/build/obj/main/debug/a7x4xr44ztlqwmvcholpy7aub/lib.o

> Task :app:compileDebugCpp
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/message.cpp -o ./app/build/obj/main/debug/f0c36b8y9kkzr0hshipmuavim/message.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/options.txt ./app/src/main/cpp/main.cpp -o ./app/build/obj/main/debug/5v08dtn7cszvzrlgdayn1y9u6/main.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/ebel2vnfw5438u8fmr181tbvm/options.txt ./app/src/main/cpp/another-source-with-warnings.cpp -o ./app/build/obj/main/debug/bf8jm0n2b8vc37bvbcxj7osrh/another-source-with-warnings.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/ebel2vnfw5438u8fmr181tbvm/options.txt ./app/src/main/cpp/a-source-with-warnings.cpp -o ./app/build/obj/main/debug/9xgvrlxlkixzbtr630iark821/a-source-with-warnings.o
Starting process 'command '/usr/bin/clang++''. Working directory: ./app/build/obj/main/debug Command: /usr/bin/clang++ @./app/build/tmp/compileDebugCpp/ea7qb4nylgh7o92vfh4uvv3b9/options.txt ./app/src/main/cpp/another.cpp -o ./app/build/obj/main/debug/8uoynvvfkn4nasuqjbq9fvwuf/another.o

BUILD SUCCESSFUL
```

Notice the compilation invocation uses different `options.txt`.
The compile task divide each source into its own compile options bucket.
You can view the final chosen options in those new files.

```shell {exemplar}
$ find ./app/build/tmp/compileDebugCpp -name options.txt
./app/build/tmp/compileDebugCpp/ebel2vnfw5438u8fmr181tbvm/options.txt
./app/build/tmp/compileDebugCpp/ea7qb4nylgh7o92vfh4uvv3b9/options.txt
./app/build/tmp/compileDebugCpp/options.txt
```

There are some limitation with the per-source options.
In Java source code, use `dev.nokee.language.cpp.tasks.CppCompile` task type to access the per-source options.
