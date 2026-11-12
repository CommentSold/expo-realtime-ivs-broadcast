#!/usr/bin/env bash
# Downloads the Amazon IVS Broadcast SDK (Stages variant) xcframework into
# ios/Frameworks, which the podspec vendors. Idempotent: skips the download when
# the pinned version is already present.
#
# AWS distributes the SDK only via SPM or direct download since 1.39.0
# (no CocoaPods). Bump IVS_SDK_VERSION together with the Android
# dependency in android/build.gradle.
set -euo pipefail

IVS_SDK_VERSION="${IVS_SDK_VERSION:-1.48.1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$HERE/ios/Frameworks"
MARKER="$DEST/.ivs-sdk-version"

if [[ -f "$MARKER" && "$(cat "$MARKER")" == "$IVS_SDK_VERSION" && -d "$DEST/AmazonIVSBroadcast.xcframework" ]]; then
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[expo-realtime-ivs-broadcast] downloading IVS Stages SDK $IVS_SDK_VERSION"
curl -sSfL -o "$TMP/sdk.zip" \
  "https://broadcast.live-video.net/${IVS_SDK_VERSION}/AmazonIVSBroadcast-Stages.xcframework.zip"
unzip -q "$TMP/sdk.zip" -d "$TMP/x"

rm -rf "$DEST/AmazonIVSBroadcast.xcframework"
mkdir -p "$DEST"
mv "$TMP/x/AmazonIVSBroadcast.xcframework" "$DEST/"
# dSYMs are ~170 MB of the archive and aren't needed to build.
find "$DEST/AmazonIVSBroadcast.xcframework" -type d -name dSYMs -prune -exec rm -rf {} +
echo "$IVS_SDK_VERSION" > "$MARKER"
