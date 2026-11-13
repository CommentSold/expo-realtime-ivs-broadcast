# Changelog

All notable changes to `expo-realtime-ivs-broadcast`.

## 0.5.7

- ios: fix the remaining black flash when a live opens. Viewer PiP's sample-buffer
  layer can start showing live frames before the remote view builds its
  `IVSImagePreviewView`; the new preview is opaque black until its first frame and
  covered the live video for ~100 ms (a timing race, ~50% of starts). The preview now
  starts at 0.01 alpha for 0.25 s when the PiP layer is already behind it, so the
  layer's identical video shows through.

## 0.5.6

- ios: shorten the PiP stop-button fallback (controller teardown) from 0.8 s to 0.3 s.
  `stopPictureInPicture()` is always ignored from the stop button, so the teardown is
  the real path; the window now closes sooner.
- ios: viewer PiP no longer enqueues black placeholder frames while real frames are
  flowing. They sat in the on-screen sample-buffer layer and flickered over the live
  video for ~100 ms right after a live opened (the layer alternated between black
  placeholders and real frames during the 0.5 s pre-warm).

## 0.5.5

- ios: fix the PiP stop button. 0.5.3's diagnosis (off-main call) was wrong: the
  callback was already on main and `stopPictureInPicture()` was still ignored when
  called synchronously from `setPlaying`. Now the playback state is reported paused,
  the stop is requested 150 ms later, and if iOS still hasn't begun stopping after
  0.8 s the controller is torn down (closing the window) and `stopped` is emitted.

## 0.5.4

- ios: the podspec now runs `scripts/fetch-ios-sdk.sh` on every `pod install`, so the
  vendored IVS xcframework is restored after `node_modules` is re-extracted
  (previously only `expo prebuild` fetched it).
- ios: the download is verified against a pinned SHA-256 per SDK version.

## 0.5.3 — 2026-11-12

### Fixed

- **iOS viewer PiP: the stop button did nothing.** 0.5.2 called
  `stopPictureInPicture()` from the playback delegate, which AVKit invokes off
  the main thread, where the call is ignored (device log: `setPlaying: false`
  fired, PiP kept rendering, no `Will stop`). It now hops to the main queue.

## 0.5.2 — 2026-11-12

### Changed

- **iOS viewer PiP: the center stop button now stops.** The sample-buffer PiP
  window shows a stop (square) button for live content, and it used to do
  nothing. Tapping it now ends PiP without restoring the app (same
  `onPiPStateChanged: stopped` as the X), so consumers close the live. There is
  no public API to hide the button.

## 0.5.1 — 2026-11-12

### Fixed

- **iOS viewer: a dark "LIVE / Broadcasting in progress" card flashed for ~0.3s
  when opening a live.** Since 0.5.0 the PiP sample-buffer layer sits behind the
  remote view, so the PiP pre-warm frames showed through until IVS painted its
  first frame. Viewer (remote) PiP now pre-warms with solid black and no longer
  switches to the branded card when frames stall (it holds the last frame). The
  branded card remains for broadcaster (local) PiP.

## 0.5.0 — 2026-11-12

### Changed

- **iOS viewer PiP now uses the sample-buffer playback source** (`sourceView: 'remote'`).
  The video-call API window has no system controls, and touches never reach
  custom views in it (so the 0.4.1 close "X" was inert). The sample-buffer
  window gets the system's own controls: close (X) and restore. Because the
  system now owns the window, tapping its body reveals the controls instead of
  restoring the app immediately; the expand button restores.
  - The sample-buffer layer is hosted behind the remote stream view (on screen
    but covered), fed by the existing `IVSImageDevice` frame callback.
  - Live-stream playback: unbounded time range, `requiresLinearPlayback`
    (no skip buttons), frames flagged display-immediately. Pause is not
    supported; the play/pause button re-asserts "playing".
  - Broadcaster PiP (`sourceView: 'local'`) is unchanged (video-call API).

### Removed

- The inert PiP close button added in 0.4.1.

## 0.4.1 — 2026-11-12

### Added

- **iOS: close button in the PiP window.** The video-call PiP window has no
  system close control, so the PiP content view now draws an "X" (top-left).
  It stops PiP without restoring the app; consumers see `onPiPStateChanged`
  `stopped` with no preceding `restored`. Tapping anywhere else still restores
  the app. Play/pause and skip are not provided (live stream, video-call PiP).

## 0.4.0 — 2026-11-12

CommentSold fork. Focus: production hardening for the viewer (subscribe-only) path.

### Changed

- **IVS Broadcast SDK bumped: Android 1.31.0 → 1.48.1, iOS 1.36 → 1.48.1.**
  Both platforms compile clean (iOS Debug + Release, Android `compileDebugKotlin`).
  Android emits new deprecation warnings for `Stage.Strategy` and
  `Stage.SubscribeType`; migration to the replacement APIs is a follow-up.
- **iOS now vendors the SDK xcframework instead of the CocoaPods dependency.**
  AWS stopped publishing to CocoaPods at 1.39.0. `scripts/fetch-ios-sdk.sh`
  downloads the Stages xcframework into `ios/Frameworks/` (gitignored); the
  podspec vendors it. New `app.plugin.js` config plugin runs the script during
  `expo prebuild`. Add `"expo-realtime-ivs-broadcast"` to the app's `plugins`.
  Override the version with `IVS_SDK_VERSION`. Keep it in sync with
  `android/build.gradle`.

### Fixed

- **A failed join now reads the same on both platforms.** iOS emitted
  `onStageConnectionStateChanged` with `state: "error"` (not in the declared
  `connecting | connected | disconnected` type); Android emitted no connection
  event at all. Both now emit `onStageError` (fatal) plus a connection state of
  `disconnected` with an `error` string.

## 0.3.2 — 2026-08-25

### Fixed

- **Remote and preview views could letterbox a live stream despite `scaleMode="fill"`.**
  `fill` is the library's default everywhere except the four prop setters
  (iOS + Android, remote + stage preview), which fell back to `fit`. On iOS the
  remote view's prop is optional, so a render where it arrived `nil` set `fit`,
  and `updateScaleMode` rebuilt the live preview letterboxed. Measured in a
  production app on iOS 26: a 9:16 source centred with symmetric 182 px bands
  while JS passed `fill` throughout. All setters now default to `fill` for both
  `nil` and unrecognised values, and Android's aspect mapping no longer falls
  through to `FIT`. Passing a valid `"fit"` or `"fill"` behaves as before.

## 0.3.1 — 2026-08-20

### Fixed

- **iOS: viewers received a mirrored broadcast from the front camera.**
  `isVideoMirrored` was set on the `AVCaptureVideoDataOutput` connection, whose
  sample buffers are what IVS publishes, so the mirror shipped to every viewer
  (text backwards, the room reversed). The host couldn't tell, because their
  preview mirrors through a separate `AVCaptureVideoPreviewLayer`. The preview
  keeps its mirror; the published output no longer has one.

## 0.3.0 — 2026-08-15

### Tooling

- **Expo SDK 57 / React Native 0.86 compatibility.** devDependencies bumped
  (expo ~57, react-native 0.86.2, expo-module-scripts 56, explicit typescript),
  and all type-only imports converted for the `verbatimModuleSyntax` tsconfig
  that new expo-module-scripts enables. No runtime behavior changes.
- Example app regenerated with `expo prebuild --clean` on SDK 57; Android
  example builds green. iOS requires Xcode 26.4+ (SDK 56+ minimum).

## 0.2.9 — 2026-05-13

### Broadcaster

- **Default framerate bumped from 15 fps to 30 fps** on both iOS and Android.
  The IVS Stages SDK defaults to 15 fps which is too choppy for product demos
  in live commerce contexts. Every code path (initial setup, native camera
  stream, custom camera capture, camera swap, mock streams) now lands on 30 fps
  unless JS explicitly overrides via `LocalVideoConfig.targetFramerate`.
- **Default video bitrate range tuned for quality**: 0.5–2.5 Mbps (was: SDK
  defaults of ~0.1–1.5 Mbps). WebRTC's adaptive bitrate scales freely within
  this range — on good networks the encoder pushes up to 2.5 Mbps for clean
  720p portrait detail; on poor networks it stays above the 500 kbps floor so
  the picture remains readable instead of collapsing into block artifacts.
- Added `IVSStageManager.defaultVideoConfig()` static helper on iOS to
  centralize the default video configuration. All fallback paths now share
  the same tuned settings instead of each setting frame size and inheriting
  SDK defaults for framerate and bitrate.

### Viewer

- **`tickRTCStats()` now falls back to a remote video stream** when no local
  camera exists (iOS + Android). Previously the timer early-returned for
  viewer-only sessions (no `cameraStream`), so subscribers received zero
  `onRTCStats` events — making any viewer-side stats HUD impossible. The
  fallback prefers the targeted participant (if `joinStage` was called with
  one) and otherwise picks the first remote participant with a VIDEO stream.
- **`leaveStage()` now proactively emits `onParticipantLeft` for each
  remote participant and clears its internal participants array.** This
  fixes the close→reopen black-screen race where stale participants from
  session A leaked into session B's `useStageParticipants` state and the
  viewer's remote stream view tried to attach to a participant that didn't
  exist on the new stage. The SDK's own disconnect event still fires
  asynchronously and could race the new session's `joinStage()`, wiping
  freshly-arrived state — proactive clearing here closes that window.

### Performance

- `ExpoIVSRemoteStreamView` JS wrapper is now wrapped in `React.memo`.
  The native view already guards against duplicate `participantId` /
  `deviceUrn` assignments, but every parent re-render still serialized a
  fresh props bundle across the JS bridge. With memo, identical props
  short-circuit at the JS layer.

### Notes

- Stages SDK does not support 60 fps. If you need higher framerates, switch to
  the IVS low-latency broadcast SDK (single-publisher only, different module).
- iPhone front cameras can still auto-drop to 15 fps in low light due to
  `AVCaptureDevice` exposure logic — this is an OS-level cap, not an SDK or
  module limit. Improve lighting to keep 30 fps.
- Android: the 1.31 SDK still doesn't expose a public stats-delivery callback,
  so `onRTCStats` continues to fire only on iOS. The Android RTC stats
  fallback is in place so viewers light up automatically when we adopt a
  newer SDK.

## 0.2.8 and earlier

See git history.
