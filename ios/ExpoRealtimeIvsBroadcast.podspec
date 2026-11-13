require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# AWS stopped publishing the IVS SDK to CocoaPods at 1.39.0, so the Stages
# xcframework is downloaded here. Podspecs of autolinked (development) pods are
# evaluated on every `pod install`, and the script is idempotent, so the
# framework is restored whenever node_modules was re-extracted (e.g. bun install).
fetch_script = File.join(__dir__, '..', 'scripts', 'fetch-ios-sdk.sh')
unless system('bash', fetch_script)
  raise "[expo-realtime-ivs-broadcast] failed to download the IVS iOS SDK (#{fetch_script})"
end

Pod::Spec.new do |s|
  s.name           = 'ExpoRealtimeIvsBroadcast'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = {
    :ios => '14.0'
  }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/CommentSold/expo-realtime-ivs-broadcast' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Downloaded by ../scripts/fetch-ios-sdk.sh (run at the top of this podspec
  # and by the config plugin during `expo prebuild`) and vendored here.
  s.vendored_frameworks = 'Frameworks/AmazonIVSBroadcast.xcframework'
  s.frameworks = 'AVFoundation', 'CoreMedia', 'VideoToolbox'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  # Include all Swift files in the ios directory
  s.source_files = "**/*.swift"
end
