#!/bin/sh
# termux-ndk-setup.sh — torna o Android SDK/NDK utilizavel no Termux (aarch64).
#
# O SDK/NDK oficial so tem ferramentas de host x86_64. Este script substitui
# os binarios de host por wrappers/symlinks para os equivalentes aarch64 do
# Termux (pkg: aidl, aapt2, zipalign, cmake, ninja, clang, lld, llvm).
#
# Idempotente. Precisa ser re-executado se o NDK/build-tools forem
# reinstalados (o sdkmanager restaura os binarios x86_64).
#
# Uso: sh scripts/termux-ndk-setup.sh
set -e

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
NDK="$SDK/ndk/29.0.13846066"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
LF="$NDK/build/cmake/android-legacy.toolchain.cmake"

swap() { # swap <dir> <bin> <substituto>
  if [ -f "$1/$2" ] && file "$1/$2" | grep -q x86-64; then
    mv "$1/$2" "$1/$2.x86_64.bak"
    cp "$3" "$1/$2" 2>/dev/null || ln -sf "$3" "$1/$2"
    echo "trocado: $1/$2"
  fi
}

# build-tools
swap "$BT" aidl "$PREFIX/bin/aidl"
swap "$BT" zipalign "$PREFIX/bin/zipalign"

# NDK: binarios de host -> Termux
for t in clang-21 lld llvm-ar llvm-objcopy llvm-nm llvm-readobj llvm-dwarfdump llvm-symbolizer llvm-strings llvm-cxxfilt; do
  if [ -f "$BIN/$t" ] && file "$BIN/$t" | grep -q x86-64; then
    mv "$BIN/$t" "$BIN/$t.x86_64.bak"
    ln -sf "$PREFIX/bin/$t" "$BIN/$t"
    echo "trocado: NDK bin/$t"
  fi
done

# NDK: host "Android" (Termux) usa o toolchain linux-x86_64 (agora arm64)
if ! grep -q "Termux host patch" "$LF"; then
  sed -i 's|^if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux)$|if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux OR CMAKE_HOST_SYSTEM_NAME STREQUAL Android)  # Termux host patch|' "$LF"
  echo "patch: android-legacy.toolchain.cmake"
fi

# NDK: o driver do clang do Termux traduz -static-libstdc++ para
# "-Bstatic -lc++_shared", que nao existe como .a no NDK. Criamos um
# libc++_shared.a REAL (linker script GROUP) apontando para static+abi.
# NUNCA escrever via symlink (foi assim que o libc++_static.a original foi
# destruido uma vez — o GROUP acabou dentro dele e o lld crashou).
for d in aarch64-linux-android arm-linux-androideabi i686-linux-android x86_64-linux-android; do
  if [ -f "$SR/$d/libc++_static.a" ] && ! [ -f "$SR/$d/libc++_shared.a" ]; then
    printf 'GROUP ( libc++_static.a libc++abi.a )\n' > "$SR/$d/libc++_shared.a"
    echo "criado: sysroot $d/libc++_shared.a"
  fi
done

# aapt2 aarch64 (override global do gradle)
if ! grep -q aapt2FromMavenOverride ~/.gradle/gradle.properties 2>/dev/null; then
  echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
  echo "patch: ~/.gradle/gradle.properties (aapt2 override)"
fi

echo "OK — build nativo pronto no Termux."
