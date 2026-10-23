#!/usr/bin/env bash
# Release APKs since the Nuvio RS rename. Beta and Stable releases carry:
#   NuvioRS-TV-<tag>-{arm64,arm32,x64,x32,any}.apk   Nuvio RS (com.nuvioreshaped.tv)
# Tester tags (-tester.N) build and publish only arm32 for faster iteration.
# The legacy bridge APK (com.nuviodebug.com), which moved installs from before the rename to
# Nuvio RS, is no longer built.
#
#   reshaped-tv-apks.sh build <tag>                  build the APKs into release-apks/
#   reshaped-tv-apks.sh verify <tag> [previous-tag]  check package id, signature and version
#   reshaped-tv-apks.sh list <tag>                   print the asset paths
set -euo pipefail

OUT="release-apks"
SOURCE_DIR="app/build/outputs/apk/full/release"
RESHAPED_PACKAGE="com.nuvioreshaped.tv"

reshaped_apk() { echo "$OUT/NuvioRS-TV-$1-$2.apk"; }

is_tester() { [[ "$1" =~ -tester[.][0-9]+$ ]]; }

apk_pairs() {
  if is_tester "$1"; then
    echo "armeabi-v7a:arm32"
  else
    echo "arm64-v8a:arm64 armeabi-v7a:arm32 x86_64:x64 x86:x32 universal:any"
  fi
}

build() {
  local tag="$1"
  mkdir -p "$OUT"
  local -a gradle_args=()
  if is_tester "$tag"; then
    gradle_args+=(-PtesterArm32Only=true)
  fi
  ./gradlew :app:assembleFullRelease "${gradle_args[@]}" --build-cache --stacktrace
  local pair abi alias
  for pair in $(apk_pairs "$tag"); do
    abi="${pair%%:*}"
    alias="${pair##*:}"
    test -f "$SOURCE_DIR/app-full-${abi}-release.apk"
    mv "$SOURCE_DIR/app-full-${abi}-release.apk" "$(reshaped_apk "$tag" "$alias")"
  done
  ls -l "$OUT"
}

tools() {
  AAPT="$(find "$ANDROID_HOME/build-tools" -type f -name aapt | sort -V | tail -n 1)"
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
  test -x "$AAPT"
  test -x "$APKSIGNER"
}
package_of() { "$AAPT" dump badging "$1" | head -n 1 | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
version_of() { "$AAPT" dump badging "$1" | head -n 1 | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"; }
cert_of() {
  "$APKSIGNER" verify --print-certs "$1" 2>&1 |
    sed -nE 's/.*certificate SHA-256 digest:[[:space:]]*([^[:space:]]+).*/\1/p' | head -n 1
}

# Downloads the first asset of <tag> matching one of the patterns into <dir>; prints its path.
download_previous() {
  local tag="$1" dir="$2"
  shift 2
  mkdir -p "$dir"
  local pattern
  for pattern in "$@"; do
    gh release download "$tag" --repo "$GITHUB_REPOSITORY" --pattern "$pattern" --dir "$dir" 2>/dev/null || true
    local found
    found="$(find "$dir" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
    if [[ -n "$found" ]]; then
      echo "$found"
      return
    fi
  done
}

# The new APK must install over the previous one: same package and signer, higher versionCode.
check_update() {
  local new="$1" old="$2" label="$3"
  local new_cert old_cert
  new_cert="$(cert_of "$new")"
  old_cert="$(cert_of "$old")"
  echo "$label: $(package_of "$old") $(version_of "$old") -> $(package_of "$new") $(version_of "$new")"
  test -n "$new_cert"
  test "$(package_of "$old")" = "$(package_of "$new")"
  test "$old_cert" = "$new_cert"
  (( $(version_of "$new") > $(version_of "$old") ))
}

verify() {
  local tag="$1" previous="${2:-}"
  tools
  local reshaped alias=arm64
  if is_tester "$tag"; then alias=arm32; fi
  reshaped="$(reshaped_apk "$tag" "$alias")"
  test "$(package_of "$reshaped")" = "$RESHAPED_PACKAGE"

  if [[ -n "$previous" ]]; then
    local old
    old="$(download_previous "$previous" previous-reshaped "NuvioRS-*-${alias}.apk" 'NuvioRS-*-arm32.apk' 'NuvioRS-*-arm64.apk')"
    if [[ -n "$old" ]]; then
      check_update "$reshaped" "$old" "Nuvio RS"
    else
      echo "Nuvio RS: first release under $RESHAPED_PACKAGE"
    fi
  fi
}

list() {
  local tag="$1" pair
  for pair in $(apk_pairs "$tag"); do
    reshaped_apk "$tag" "${pair##*:}"
  done
}

"$@"

