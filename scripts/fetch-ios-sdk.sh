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

# SHA-256 of AmazonIVSBroadcast-Stages.xcframework.zip per version. Add an entry
# when bumping IVS_SDK_VERSION (`shasum -a 256` of the downloaded zip), or set
# IVS_SDK_SHA256 to override for a one-off version.
case "$IVS_SDK_VERSION" in
  1.48.1) KNOWN_SHA256="9971208134c0ee63e9775001b44f1b879bc991192e47ed600eb9e1a292084b3b" ;;
  *) KNOWN_SHA256="" ;;
esac
EXPECTED_SHA256="${IVS_SDK_SHA256:-$KNOWN_SHA256}"
if [[ -z "$EXPECTED_SHA256" ]]; then
  echo "[expo-realtime-ivs-broadcast] no pinned SHA-256 for IVS SDK $IVS_SDK_VERSION; add one to scripts/fetch-ios-sdk.sh or set IVS_SDK_SHA256" >&2
  exit 1
fi
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
ACTUAL_SHA256="$(shasum -a 256 "$TMP/sdk.zip" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  echo "[expo-realtime-ivs-broadcast] SHA-256 mismatch for IVS SDK $IVS_SDK_VERSION: expected $EXPECTED_SHA256, got $ACTUAL_SHA256" >&2
  exit 1
fi
unzip -q "$TMP/sdk.zip" -d "$TMP/x"

rm -rf "$DEST/AmazonIVSBroadcast.xcframework"
mkdir -p "$DEST"
mv "$TMP/x/AmazonIVSBroadcast.xcframework" "$DEST/"
# dSYMs are ~170 MB of the archive and aren't needed to build.
find "$DEST/AmazonIVSBroadcast.xcframework" -type d -name dSYMs -prune -exec rm -rf {} +
echo "$IVS_SDK_VERSION" > "$MARKER"
