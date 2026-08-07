#!/usr/bin/env bash
set -e
NDKBIN="/e/Android/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin"
SPG=$(cygpath -w /e/.Gits/spglib)
GEN=$(cygpath -w /e/Android/spglib-build/gen)
SRCS="arithmetic cell debug delaunay determination hall_symbol kgrid kpoint magnetic_spacegroup mathfunc msg_database niggli overlap pointgroup primitive refinement site_symmetry sitesym_database spacegroup spg_database spglib spin symmetry"

declare -A TC=(
  [arm64-v8a]=aarch64-linux-android24-clang
  [armeabi-v7a]=armv7a-linux-androideabi24-clang
  [x86_64]=x86_64-linux-android24-clang
  [x86]=i686-linux-android24-clang
)

for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  OUT_MSYS="/e/Android/spglib-build/$abi"
  ABIOUT=$(cygpath -w "$OUT_MSYS")
  rm -rf "$OUT_MSYS/obj"
  mkdir -p "$OUT_MSYS/obj"
  echo "=== $abi ==="
  ok=1
  for s in $SRCS; do
    "$NDKBIN/${TC[$abi]}" -std=c99 -O2 -fPIC -DSPG_BUILD \
      -I"$SPG\\src" -I"$SPG\\include" -I"$GEN" \
      -c "$SPG\\src\\$s.c" -o "$ABIOUT\\obj\\$s.o" 2>>"$OUT_MSYS/compile.log" \
      || { echo "COMPILE FAIL $s"; ok=0; break; }
  done
  if [ $ok -eq 0 ]; then
    echo "--- last errors ---"
    tail -5 "$OUT_MSYS/compile.log"
    continue
  fi
  OBJS=$(ls "$OUT_MSYS"/obj/*.o | sed 's|/e/|E:/|')
  "$NDKBIN/${TC[$abi]}" -shared -o "$ABIOUT\\libspglib.so" $OBJS -lm \
    || { echo "LINK FAIL $abi"; continue; }
  echo "OK $abi: $(basename "$OUT_MSYS/libspglib.so") $(stat -c%s "$OUT_MSYS/libspglib.so") bytes"
done
echo ALLDONE
