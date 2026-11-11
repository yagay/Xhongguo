# Unified native playback ownership (reference: rewrite-web-native)

## Source design

Reference repository: `yagay/hongguo-android`, branch `rewrite-web-native`
(reference commit `52a592b95c6462e6a1e180091f6e1904ba333ae7`).

The standalone application's `BackgroundPlaybackService.kt` owns one
Media3 ExoPlayer. `ReelMediaSurface.kt` and `InAppMiniPlayer.kt` only
attach views to that same service-owned player. `DramaViewModel.kt`
separates video identity from navigation, preserving progress when opening
details or minimizing video.

## Xhongguo adaptation

The OFFICIAL Hongguo APK creates and manages TTVideoEngine internally.
Different native playback Activities can own separate TTVideoEngine
instances. It is **not** safe for an LSPosed module to replace the app's
decoder with the standalone application's ExoPlayer or to force both
Activities to reuse the same native engine instance.

Instead, `SinglePlaybackOwner.kt` ports the *single owner* policy:

1. Exactly one current TTVideoEngine has the right to play sound.
2. When a different native engine becomes active, pause the prior owner
   without releasing its resources or altering its saved position.
3. An old screen's engine may not replay while that screen's route is
   inactive. On returning to its own route it may resume.
4. A detail screen does not itself own a second media session.
5. On same-route episode changes, a native mini surface rebinds to the new
   engine; on cross-route switches the old floating surface is restored.
6. Explicit notification / mini user pause bypasses the brief navigation
   pause guard. Background playback remains controlled by SystemMediaBridge.

This uses semantic `TTVideoEngine.play/pause/releaseAsync` and native
Activity class names; it does not use legacy obfuscated method mappings.

## Native detail mini view

The app-internal mini view is prepared **before** launching known native
`ShortSeriesDetailActivity` or `VideoAlbumDetailActivity`. The existing
video-host View is transferred to the detail Activity's content frame
after resume, without creating a second player or a new media source.
Returning from details restores the original View hierarchy.

Important: moving a native Surface/Texture across Activity windows is
inherently device-dependent. A successful APK build proves source
integration, not uninterrupted video rendering. If the host tears down
its Surface, capture a runtime diagnostic; never create a second decoder
as a blind fallback.

## On-device regression checklist

- Start feed playback; open full-series video; confirm only one audio
  stream and that the previous feed engine was paused.
- Return from full series to feed; confirm playback belongs to the
  visible route, without stale engine auto-restart.
- Open details from feed and from full series; confirm mini video appears
  and retains the same timestamp; back / tap mini should restore playback.
- While mini is active, switch bottom tabs, drag/snap, explicitly pause,
  resume, and close. Manual pause must not be reversed by a lifecycle guard.
- Let an episode auto-advance in mini; verify the floating view rebinds
  rather than showing the previous episode or spawning another player.
- Test leaving the app and returning. Compare media notification ownership.
- Confirm logs: `单播放器: 已接管播放权`,
  `单播放器: 旧引擎已暂停`,
  `单播放器: 阻止旧页面播放器重新抢占`,
  `应用内小窗：详情页已接管原有视频窗口`,
  and `应用内小窗：同页面新集复用小窗`.

## Boundaries

This is one logical playback owner, NOT one physically shared
TTVideoEngine instance. If upstream starts a native engine entirely
outside the observed hook/process, or launches details through an
unlisted fragment/router, further targeted on-device logs are needed.
