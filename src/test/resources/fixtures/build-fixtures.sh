#!/usr/bin/env bash
#
# Regenerates the ELF, Mach-O and import-library fixtures the ABI extractor tests read.
#
# Usage: ./build-fixtures.sh [elf|macho|implib|all]     (default: all)
#
# ELF and the import libraries are built inside a container, so the compiler, the linker and the
# libc they link against are the same ones on every host. Mach-O is built with the host's clang,
# which needs macOS.
#
# Windows builds the import libraries with lib.exe instead; see build-fixtures.bat. Both read the
# same exports.def, and a short import object carries the same fields either way — only the
# timestamp and the hint of a named export differ, and neither is asserted on.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
image=nokee-fixture-toolchain

# Everything the container builds runs through here, against the fixtures directory.
in_container() {
	docker run --rm --platform linux/amd64 -v "$here":/fixtures -w /fixtures "$image" bash -euo pipefail -c "$1"
}

build_image() {
	if [ -n "$(docker images -q "$image" 2>/dev/null)" ]; then
		return
	fi

	echo "==> building the $image image"
	docker build --platform linux/amd64 -t "$image" - <<-'DOCKERFILE'
		FROM debian:bookworm-slim
		RUN apt-get update && apt-get install -y --no-install-recommends \
		      gcc binutils libc6-dev \
		      gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu libc6-dev-arm64-cross \
		      llvm \
		 && rm -rf /var/lib/apt/lists/*
	DOCKERFILE
}

# A shared library per architecture, each with a SONAME of its own so the reader has one to report.
# -fPIC because a shared library needs it, and the symbols themselves carry the visibility the
# source asks for rather than a command-line default.
elf_shared_library() {
	local dir=$1 source=$2 soname=$3 extra=${4:-}
	in_container "
		for arch in x86_64 aarch64; do
			mkdir -p '$dir'/elf/\$arch
			cc=\$([ \$arch = x86_64 ] && echo gcc || echo aarch64-linux-gnu-gcc)
			\$cc -shared -fPIC -O1 $extra -Wl,-soname,$soname -o '$dir'/elf/\$arch/$soname '$dir/$source'
		done
	"
}

build_elf() {
	build_image

	echo "==> ELF: library-exports"
	elf_shared_library library-exports exports.c libexports.so

	echo "==> ELF: no-exports"
	elf_shared_library no-exports no-exports.c libnoexports.so

	echo "==> ELF: versioned-exports"
	elf_shared_library versioned-exports versioned.c libversioned.so \
		"-Wl,--version-script,versioned-exports/versioned.map"

	echo "==> ELF: thread-local-exports"
	elf_shared_library thread-local-exports thread-local.c libthreadlocal.so

	echo "==> ELF: static-archive"
	# Deterministic (-D) so a rebuild of an unchanged source leaves the archive's bytes alone.
	in_container '
		for arch in x86_64 aarch64; do
			mkdir -p static-archive/elf/$arch
			cc=$([ $arch = x86_64 ] && echo gcc || echo aarch64-linux-gnu-gcc)
			ar=$([ $arch = x86_64 ] && echo ar || echo aarch64-linux-gnu-ar)
			$cc -c -O1 -o static-archive/elf/$arch/static.o static-archive/static.c
			rm -f static-archive/elf/$arch/libstatic.a
			$ar rcsD static-archive/elf/$arch/libstatic.a static-archive/elf/$arch/static.o
			rm static-archive/elf/$arch/static.o
		done
	'
}

# A dylib per architecture, each recording the same install name so the reader has one to report.
macho_shared_library() {
	local dir=$1 source=$2 name=$3
	for arch in x86_64 arm64; do
		mkdir -p "$here/$dir/macho/$arch"
		clang -arch "$arch" -dynamiclib -O1 \
			-install_name "@rpath/$name" \
			-o "$here/$dir/macho/$arch/$name" "$here/$dir/$source"
	done
}

build_macho() {
	if [ "$(uname -s)" != Darwin ]; then
		echo "Mach-O fixtures need macOS; skipping" >&2
		return
	fi

	echo "==> Mach-O: library-exports"
	macho_shared_library library-exports exports.c libexports.dylib
	# A universal binary, so the reader is exercised on a blob holding an image per architecture.
	mkdir -p "$here/library-exports/macho/universal"
	lipo -create \
		"$here/library-exports/macho/x86_64/libexports.dylib" \
		"$here/library-exports/macho/arm64/libexports.dylib" \
		-output "$here/library-exports/macho/universal/libexports.dylib"

	echo "==> Mach-O: no-exports"
	macho_shared_library no-exports no-exports.c libnoexports.dylib

	echo "==> Mach-O: static-archive"
	for arch in x86_64 arm64; do
		mkdir -p "$here/static-archive/macho/$arch"
		clang -arch "$arch" -c -O1 -o "$here/static-archive/macho/$arch/static.o" "$here/static-archive/static.c"
		rm -f "$here/static-archive/macho/$arch/libstatic.a"
		ar rcs "$here/static-archive/macho/$arch/libstatic.a" "$here/static-archive/macho/$arch/static.o"
		rm "$here/static-archive/macho/$arch/static.o"
	done
}

# One import library per architecture, from the one exports.def. The 32-bit build is what puts a
# leading underscore on a cdecl name, which is the decoration IMPORT_OBJECT_NAME_NOPREFIX stands for.
build_implib() {
	build_image

	echo "==> import-library"
	in_container '
		mkdir -p import-library/x86 import-library/x86_64
		llvm-dlltool -m i386        -d import-library/exports.def -l import-library/x86/exports.lib
		llvm-dlltool -m i386:x86-64 -d import-library/exports.def -l import-library/x86_64/exports.lib
	'
}

case "${1:-all}" in
	elf)    build_elf ;;
	macho)  build_macho ;;
	implib) build_implib ;;
	all)    build_elf; build_macho; build_implib ;;
	*)      echo "usage: $0 [elf|macho|implib|all]" >&2; exit 2 ;;
esac

echo "==> done"
