#!/usr/bin/env bash
# Builds libherdrvt: Ghostty's libghostty-vt terminal emulator plus the app's JNI glue
# (app/src/main/cpp/herdrvt.c), for the R1 and for this computer's JVM tests.
#
#   app/src/main/jniLibs/arm64-v8a/libherdrvt.so   packed into the APK
#   out/herdrvt-host/libherdrvt.{dylib,so}          loaded by GhosttyVtTest when present
#
# Needs Zig 0.16 (brew install zig@0.16) and the Android NDK (sdkmanager "ndk;27.2.12479018").
# Usage: tools/build-ghostty-vt.sh
set -euo pipefail
source "$(dirname "$0")/lib.sh"

# The commit Ghostling (ghostty-org/ghostling) builds against.
GHOSTTY_COMMIT=f64f4aca2c29b554d111b36c3d946a9bddd159ff
ZIG=${ZIG:-$(command -v zig || echo /opt/homebrew/opt/zig@0.16/bin/zig)}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort | tail -1)}
API=33 # minSdk

"$ZIG" version | grep -q '^0\.16\.' || die "Zig 0.16 is required (found $("$ZIG" version)); brew install zig@0.16"
[ -d "$NDK" ] || die "No Android NDK under $SDK/ndk"
HOST_TAG=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android$API-clang"

SRC="$OUT/ghostty"
if [ ! -d "$SRC/.git" ]; then
  say "Fetching Ghostty"
  git clone -q --filter=blob:none https://github.com/ghostty-org/ghostty.git "$SRC"
fi
git -C "$SRC" fetch -q origin "$GHOSTTY_COMMIT" 2>/dev/null || true
git -C "$SRC" checkout -q "$GHOSTTY_COMMIT"

build_vt() { # <zig target or "native"> <prefix>
  local target=()
  [ "$1" != native ] && target=(-Dtarget="$1")
  (cd "$SRC" && "$ZIG" build -Demit-lib-vt ${target[@]+"${target[@]}"} -Doptimize=ReleaseFast --prefix "$2")
}

say "libghostty-vt for the R1 (aarch64-linux-android)"
build_vt aarch64-linux-android "$OUT/ghostty-vt-android"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
"$CLANG" -shared -fPIC -O2 -I"$OUT/ghostty-vt-android/include" "$ROOT/app/src/main/cpp/herdrvt.c" \
  "$OUT/ghostty-vt-android/lib/libghostty-vt.a" -Wl,--gc-sections -s -o "$ROOT/app/src/main/jniLibs/arm64-v8a/libherdrvt.so"

say "libghostty-vt for this computer (JVM tests)"
build_vt native "$OUT/ghostty-vt-host"
JDK=${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)}
mkdir -p "$OUT/herdrvt-host"
case "$(uname)" in
  Darwin) cc -dynamiclib -O2 -I"$JDK/include" -I"$JDK/include/darwin" -I"$OUT/ghostty-vt-host/include" "$ROOT/app/src/main/cpp/herdrvt.c" \
            "$OUT/ghostty-vt-host/lib/libghostty-vt.a" -o "$OUT/herdrvt-host/libherdrvt.dylib" ;;
  *)      cc -shared -fPIC -O2 -I"$JDK/include" -I"$JDK/include/linux" -I"$OUT/ghostty-vt-host/include" "$ROOT/app/src/main/cpp/herdrvt.c" \
            "$OUT/ghostty-vt-host/lib/libghostty-vt.a" -o "$OUT/herdrvt-host/libherdrvt.so" ;;
esac

ls -l "$ROOT/app/src/main/jniLibs/arm64-v8a/libherdrvt.so" "$OUT"/herdrvt-host/libherdrvt.*
