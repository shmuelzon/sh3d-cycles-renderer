#!/bin/bash
# Collects sh3d-cycles and everything it needs at runtime into a directory that the plugin packages as
# native/<target>/ and extracts on first use. Also writes files.txt: a content hash, then the list of files.
#
# Usage: package.sh <target> <Cycles install directory> <Blender directory> <output directory> <precompiled libraries>
set -euo pipefail

TARGET=$1
INSTALL=$(cd "$2" && pwd)
BLENDER=$3
OUT=$4
LIBS=$(cd "$5" && pwd)

rm -rf "$OUT"
mkdir -p "$OUT/license"

case $TARGET in
    linux-*)
        mkdir -p "$OUT/lib"
        cp "$INSTALL/sh3d-cycles" "$OUT/"
        # Shared libraries found in the install directory (through the $ORIGIN/lib runpath), under their soname
        for lib in $(LD_LIBRARY_PATH="$INSTALL/lib" ldd "$INSTALL/sh3d-cycles" | awk -v dir="$INSTALL" 'index($3, dir) == 1 {print $1}'); do
            cp -L "$INSTALL/lib/$lib" "$OUT/lib/$lib"
        done
        # Loaded at runtime by Open Image Denoise: CPU and AMD GPU devices
        for lib in "$INSTALL"/lib/libOpenImageDenoise_device_cpu.so.* "$INSTALL"/lib/libOpenImageDenoise_device_hip.so.*; do
            [ -f "$lib" ] && cp -L "$lib" "$OUT/lib/"
        done
        ;;
    macos-*)
        mkdir -p "$OUT/lib"
        cp "$INSTALL/sh3d-cycles" "$OUT/"
        for lib in "$INSTALL"/lib/*.dylib; do
            case $(basename "$lib") in
                *device_cuda*|*device_sycl*) ;;
                *) cp -L "$lib" "$OUT/lib/" ;;
            esac
        done
        ;;
    windows-*)
        cp "$INSTALL/sh3d-cycles.exe" "$OUT/"
        # DLLs next to the executable, where Windows looks for them first: the ones it imports, recursively (with
        # dumpbin, from Visual Studio), found in the install directory or the precompiled libraries, plus Open Image
        # Denoise's CPU and AMD GPU devices that it loads at runtime
        find_dll() {
            find "$INSTALL" "$LIBS"/*/bin "$LIBS"/*/lib -maxdepth 1 -iname "$1" 2>/dev/null | head -1
        }
        queue=(sh3d-cycles.exe)
        for name in OpenImageDenoise_device_cpu.dll OpenImageDenoise_device_hip.dll ur_loader.dll; do
            dll=$(find_dll "$name")
            [ -n "$dll" ] && cp "$dll" "$OUT/" && queue+=("$(basename "$dll")")
        done
        # Visual C++ runtime, which may not be installed on the user's machine
        if [ -n "${VCToolsRedistDir:-}" ]; then
            cp "$(cygpath -u "$VCToolsRedistDir")"/x64/Microsoft.VC*.CRT/*.dll "$OUT/"
        fi
        system32=$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32
        missing=()
        while [ ${#queue[@]} -gt 0 ]; do
            file=${queue[0]}
            queue=("${queue[@]:1}")
            for dependency in $(dumpbin -nologo -dependents "$OUT/$file" | tr -d '\r' | awk 'tolower($1) ~ /\.dll$/ {print $1}'); do
                [ -n "$(find "$OUT" -maxdepth 1 -iname "$dependency")" ] && continue
                dll=$(find_dll "$dependency")
                if [ -n "$dll" ]; then
                    cp "$dll" "$OUT/$dependency"
                    queue+=("$dependency")
                elif [ -z "$(find "$system32" -maxdepth 1 -iname "$dependency" 2>/dev/null)" ] && [[ $dependency != api-ms-* ]] \
                    && [[ ! $dependency =~ ^(amdhip64|nvcuda|nvoptix) ]]; then # GPU drivers, used only with their device
                    missing+=("$dependency (needed by $file)")
                fi
            done
        done
        echo "DLLs: $(cd "$OUT" && ls *.dll | tr '\n' ' ')"
        if [ ${#missing[@]} -gt 0 ]; then
            echo "Missing DLLs: ${missing[*]}" >&2
            exit 1
        fi
        ;;
    *)
        echo "Unsupported target $TARGET" >&2
        exit 1
        ;;
esac

# Git LFS pointers instead of binaries: libraries missing from CYCLES_LIBS_INCLUDE
pointers=$(cd "$OUT" && grep -rl --binary-files=text -m1 '^version https://git-lfs' . 2>/dev/null || true)
if [ -n "$pointers" ]; then
    echo "Not downloaded from Git LFS (add them to CYCLES_LIBS_INCLUDE in versions.mk): $pointers" >&2
    exit 1
fi

# Cycles' kernel sources, from which GPU kernels are compiled on first use (Metal, or HIP with AMD's HIP SDK installed),
# as no precompiled GPU kernels are bundled
[ -d "$INSTALL/source" ] && cp -R "$INSTALL/source" "$OUT/"
[ -d "$INSTALL/license" ] && cp -R "$INSTALL/license/." "$OUT/license/"
cp -R "$BLENDER/release/datafiles/colormanagement" "$OUT/colormanagement"
cp "$BLENDER/release/license/license.md" "$OUT/license/blender-license.md"

if command -v sha1sum > /dev/null; then
    SHA1SUM=sha1sum
else
    SHA1SUM="shasum -a 1"
fi
LIST=$(mktemp)
(cd "$OUT" && find . -type f | sed 's|^\./||' | LC_ALL=C sort) > "$LIST"
{
    (cd "$OUT" && tr '\n' '\0' < "$LIST" | xargs -0 cat | $SHA1SUM | cut -c1-12)
    cat "$LIST"
} > "$OUT/files.txt"
rm "$LIST"
echo "$(du -sh "$OUT" | cut -f1) in $(($(wc -l < "$OUT/files.txt") - 1)) files"
