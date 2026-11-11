# Xhongguo beta: runtime stability refactor

This branch is deliberately separate from `main`. It retains the original
package name, LSPosed scope, settings keys, and original Hongguo video engine.
It does not introduce a second player, media decoder, foreground service, or
new video-network endpoint.

## Why the refactor exists

The 2026-11-11 21:33 diagnostic showed these distinct problems:

- Feed: native engine could play while `HomeFeedMediaInfo` returned no
  verified current-item metadata (no title, episode or official poster).
- Final episode -> next drama: the native engine switched before the official
  presenter and details resolved the next drama; the old media card was
  invalidated but the next title/poster did not appear.
- DTO callbacks arrived at different times from engine.play and were previously
  dropped inside a 1.5 s throttling window.
- Polling UI trees every 150 ms made the compatibility layer unusually costly,
  especially during video transitions.

## Changes in this beta baseline

1. `MediaRefreshCoordinator` schedules bounded, generation-safe probes
   (160 ms through 30 seconds) after a real native video transition. Native
   DTO changes get both leading and trailing debounced refresh. Obsolete
   callbacks are removed after a new transition or when media is disabled.
2. `MediaPresentationState` owns transitional notification labels. On a
   different, unverified series, it displays an honest loading label instead
   of accidentally retaining the previous drama. The MediaSession and
   Notification share exactly the same presentation policy.
3. `SystemMediaBridge` retries a failed official-poster fetch after a
   15-second backoff; it still refuses episode screenshots and checks both
   native engine identity and artwork generation before writing to a card.
4. `HomeFeedMediaInfo` uses a native ViewPager2 active adapter rather than
   a fixed obfuscated adapter base class. It additionally checks the selected
   RecyclerView holder when available, rejects non-idle pages, and throttles
   full-tree fallback scans.
5. `HookCircuitBreaker` protects optional `ham`/`hac` hooks. A repeatedly
   failing hook is bypassed while the original call is left intact. A failed
   callback is never retried by calling `chain.proceed()` a second time.
6. `SinglePlaybackOwner.reset()` drops stale ownership bookkeeping when
   background media and mini-player are both disabled, without pausing the
   actual original-app player.
7. `UiScanPolicy` adapts compatibility-scan frequency and skips full-view
   reflection when the Activity lacks focus. A scan collects window roots
   once rather than separately for controls, progress and refresh features.
8. The beta Debug workflow runs JUnit policy regression tests and builds only
   `:app:assembleDebug`.

## Non-negotiable invariants

- A native `TTVideoEngine` play command alone is not proof of title/series ID.
- A new engine may belong to the same drama; confirmed matching series ID
  allows the existing official poster to survive episode changes.
- A new drama must not show the previous drama's title or poster.
- A late bitmap or DTO callback must not update a newer playback generation.
- Playback commands and user-requested pauses must not be mistaken for host
  lifecycle pauses. Never auto-resume a user-paused video.
- The app's original video engine owns all decoding. Do not create a hidden
  second player.
- Hook failure handling must never run the host method twice.
- A build is **not** evidence that background/lock-screen, home feed,
  final-episode autoplay or actual UI behavior passed device testing.

## Manual regression checklist

- [ ] Open feed and verify current title/official poster in the media card.
- [ ] Swipe between feed videos quickly, verify no previous cover leaks.
- [ ] Return to feed and switch to a different app via Home and Recents.
- [ ] Pause from the notification, confirm it is never auto-resumed.
- [ ] Enter a full series and switch episodes within the same drama.
- [ ] Play the last episode until the next drama begins automatically.
- [ ] Confirm next drama title, episode and correct official poster once the
      native source DTO arrives; unknown fields must be marked temporarily.
- [ ] Switch between native feed and full series and restore the same owner.
- [ ] Use and close in-app mini-player, checking Surface and parent restoration.
- [ ] Enable/disable system media and mini-player without force-stopping host.
- [ ] Toggle Mine-page cleanup items, native UI hide options and menu entry.
- [ ] Test with module disabled: no periodic expensive UI traversal.
- [ ] Export diagnostic ZIP after reproduction and inspect isolated failure
      counters / media refresh markers.

## Limits and follow-up architecture

The Xhongguo module cannot guarantee continued video playback if Android
destroys the original host process or decoder. Full app-level architectural
migration remains a separate task: isolate UI menus/settings further from
`Hooks.kt`, integrate more direct hook sites into circuit breakers, and
extract official DTO resolution from the media bridge. None of these should
be merged into main without a device-level regression pass.
