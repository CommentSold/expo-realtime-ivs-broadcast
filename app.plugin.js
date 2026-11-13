const { withDangerousMod } = require('expo/config-plugins');
const { execFileSync } = require('child_process');
const path = require('path');

/**
 * Expo config plugin for expo-realtime-ivs-broadcast.
 *
 * iOS: AWS no longer publishes the IVS Broadcast SDK to CocoaPods (since
 * 1.39.0), so download the Stages xcframework (vendored by the podspec) before
 * `pod install` runs. The script is idempotent, so repeat prebuilds don't
 * re-download.
 *
 * Android: nothing to do. The SDK comes from Maven Central (android/build.gradle).
 */
module.exports = (config) =>
  withDangerousMod(config, [
    'ios',
    (config) => {
      execFileSync('bash', [path.join(__dirname, 'scripts', 'fetch-ios-sdk.sh')], {
        stdio: 'inherit',
      });
      return config;
    },
  ]);
