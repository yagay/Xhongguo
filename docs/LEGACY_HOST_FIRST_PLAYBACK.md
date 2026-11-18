# Original-host-first playback refactor · Hongguo 7.3.5.32

## Evidence and reason

The uploaded official APK's `classes4.dex` defines:

- `com.ss.ttvideoengine.TTVideoEngine.play():void`
- `TTVideoEngine.pause():void`
- `TTVideoEngine.setVideoModel(VideoModel):void`
- `TTVideoEngine.getVideoModel():VideoModel`
- `x05.w.onCompletion(TTVideoEngine):void`
- `x05.w.L(TTVideoEngine):void`
- `x05.w.c0(SaasVideoData,boolean):void` (native source loader)
- `adapter.f.U1(String):void`, `adapter.a.s4(int):boolean`
- `adapter.f.g():boolean` / `adapter.a.A1():boolean`.

Unlike obfuscated pager/detail controllers, the TTVideoEngine transport and
source boundary are shared by feed and full-series playback. On the 2026-11-18
06:16:57 device diagnostic the user media button invoked original host
`g()`, original `s4()` accepted target 1, a new engine emitted
`TTVideoEngine.play`, and V3 emitted its additional eager
`one_host_resume`. There was no confirmed new playback progress in that
observation window. This supports a *possible* conflict/early-start
hypothesis; it does NOT prove that V3 resume caused the failure.

The previous user-stable main version relied primarily on
`ActivityPrePaused`, active-engine background pause protection and the
original host's autoplay. The present branch keeps that lifecycle design
and modern user-facing functionality without manufacturing a second
playback route.

## Final layering (no second decoder)

1. **One universal transport hook** in `Hooks.kt`, covering the existing
   `TTVideoEngine.play/pause/stop/releaseAsync` method observation.
   `NativeTransportCoordinator` arbitrates only legitimate
   background/mini lifecycle pause suppression. The same native hook
   continues to track engine identity, media state and owner transitions.
2. **One universal source boundary:** existing `TTVideoEngine.setVideoModel`
   hook. `NativeSeriesV3.onHostVideoModel` logs whether the actual
   accepted next engine received a VideoModel; its result is not treated
   as playback success.
3. **Official EOF remains the owner:** original
   `x05.w.onCompletion -> adapter.f.U1/s4/G4` runs untouched.
   No auto-EOF `i9()/y4()` override, synthetic next-page selection,
   early `holder.resume()`, Surface flag mutation or timer retries.
4. **Manual MediaSession next/previous:** still calls one original
   `g()/A1()`. The original user-selected, same-target, same-adapter,
   generation-bound background `s4` scope is retained only for a
   real media-button request, then consumed once. It does not authorize
   an automatic EOF substitution.
5. **Read-only progress and display:** native holder/VideoModel/engine
   progress drive V3 confirmation, artwork, episode numbering, seek
   position, playback state and the single Android MediaSession.
6. **SinglePlaybackOwner** only adopts confirmed Series next engines
   from V3's real progress; do not adopt an unprepared replacement
   engine just because its `play()` method was entered.

## This branch deliberately does not change

- Activity/task launching and the separate feed-to-series foreground
  takeover (that issue requires an Activity-independent native data
  handoff, not an `ActivityOptions` patch).
- The stable source branches. This refactor lives only on
  `refactor/legacy-host-first-media`.
- The host's DRM, VideoModel URL decoding or network requests.
- Other system-media metadata, cover art, notification, seek and
  module diagnostics functions.

## Validation

Run Debug-only CI and tests. Test four routes separately:
1. Full-series video, leave via Home, let official EOF pass **two episodes**.
2. Full-series video, change to another app, let official EOF pass.
3. While in another app, press Android MediaSession next, then previous.
4. Return to Hongguo; ensure play actually advances rather than merely
   switching the pager.

The diagnostic should show `v3_source` after actual
`setVideoModel`, `v3_ready` on native readiness and
`v3_progress confirmed` on valid increasing native timeline.
`v3_startup observed owner=original_host` is **not** playback proof.
`v3_startup degraded original_host_no_progress` after 8s points to
a source/Surface/host readiness problem, not a reason to replay a
`resume()` or `setCurrentItem()` call.

Any assertion of success still requires a real on-device diagnostic
and full Android Debug build; local Kotlin policy tests do not verify
the module's complete APK.
