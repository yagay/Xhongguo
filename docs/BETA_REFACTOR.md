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
   of accidentally retaining the previous drama. `MediaCardSnapshot`
   atomically constructs the Android MediaMetadata from one immutable
   title/subtitle/duration/artwork snapshot. The MediaSession and
   Notification share exactly the same presentation policy.
3. `SystemMediaBridge` retries a failed official-poster fetch after a
   15-second backoff; it still refuses episode screenshots and checks both
   native engine identity and artwork generation before writing to a card.
4. `HomeFeedMediaInfo` uses a native ViewPager2 active adapter rather than
   a fixed obfuscated adapter base class. It additionally checks the selected
   RecyclerView holder when available, rejects non-idle pages, and throttles
   full-tree fallback scans. `VideoIdentityAgreement` permits video ID
   namespace mismatches only after independently selected pager data stays
   stable for at least 800 ms across multiple probes. Never trust an
   uncorroborated `getCurrentData()` mismatch.
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

## 2026-11-11 22:40:37 series metadata recovery

The user's exported diagnostic ZIP confirmed that, at 22:40:27, a new
TTVideoEngine replaced the active series player while `SaasVideoData` and
the short-lived `SaasVideoDetailModel` did not expose a comparable engine
video ID. `series-video-unverified` then erased all media-card fields, even
though the native host subsequently observed an official series model and
poster.

The beta resolver now tracks a *native engine transition epoch*, separates
DTOs actually consumed by host callbacks from passive polling of recycled
presenters, and correlates a single fresh series with a captured official
detail. This can safely recover the series title, poster, and total count
without comparing incompatible video ID namespaces. It does **not** claim a
current episode number from a possibly preloaded video.

`native_handoff_match` means the cross-namespace series evidence passed
the conservative guard. `native_handoff_probe` reports the number of fresh
native models and official-detail matches when it fails. If the data remains
ambiguous or missing, the media card switches from temporary “正在获取” to
an explicit “剧集信息暂不可用” message after 12 seconds. This is a presentation
fallback, not evidence that the real metadata was resolved.

Native data may still be too ambiguous to bind when the host preloads
multiple dramas or never supplies a matching official detail. The result
must be checked with an actual beta Debug build and new on-device diagnostics
before treating this change as validated.

## 2026-11-11 22:45:26 background episode-end regression

The exported diagnostic ZIP confirms that after the host
`ShortSeriesActivity` received `onUserLeaveHint` at 22:43:28:

- `VideoContext.onLifeCycleOnPause` and `onLifeCycleOnStop` ran.
- The native TTVideoEngine was paused, then `TTVideoEngine.play()` was
  called directly approximately one second later to resume background audio.
- Audio/video progress continued from ~17.3s to the exact 103.658s duration.
- At 22:44:53, 22:45:09 and 22:45:24, the native progress remained
  103.658/103.658s; no new video engine play event was observed.

This is evidence that **the host episode advance did not occur**, not proof
of which specific host completion callback was dropped. The previous
direct-engine recovery bypassed higher-level VideoShop state, so the beta
now prefers a guarded, exact-engine handoff:

1. During a verified `onUserLeaveHint` from an actually playing native
   page, protect only the pinned TTVideoEngine, VideoContext and controller
   from the brief host lifecycle pause/stop. Unrelated navigation, normal
   user pause, ownership switches and preloaded engines are excluded.
2. If those guards were used and no native pause event was observed, retain
   the original player instead of invoking TTVideoEngine.play() again.
3. If native progress does not advance after a bounded 2.2s check, request
   one high-level `VideoContext.play()` only for the same recorded context,
   same engine, unchanged user-pause epoch, and not near episode end.
4. Observe already registered `VideoEngineCallback.onCompletion` methods
   *after* forwarding to the host's own handler. This is optional
   diagnostics and never requests a next episode or fabricates video IDs.
5. Detect a background `positionMs == durationMs` stall lasting at least
   three seconds and record either `no_completion_callback` or
   `completion_but_no_next`. Do not loop, seek or replay at EOF.

This patch requires a new beta Debug build and real-device regression
before calling the next-episode behavior fixed. If no native completion
event arrives even with preserved VideoShop lifecycle, the next step is to
inspect the host's exact registered completion controller rather than
guess an obfuscated next-episode method.

## 2026-11-12 native APK hook-point review: Home vs direct app switch

APK inspected: the previously uploaded official `红果免费短剧(1).apk`
(~132 MB, 21 dex files). Verified native VideoShop and full-series symbols:

- `VideoController.onCompletion(TTVideoEngine)`, followed by
  `LayerHostMediaLayout.onVideoCompleted(...)` and
  `VideoContext.onVideoCompleted(...)`, then registered app listeners.
  Each stage is now observed **after** the original handler, so it cannot
  substitute an artificial next-episode command.
- `VideoContext.registerVideoPlayListener` and
  `unregisterVideoPlayListener` (if present on target build): observer
  hooks track listener registration/removal around the background handoff.
- `VideoContext.onLifeCycleOnStop()` invokes
  `releaseAllPreparedVideoControllers()`. The beta now lets the host's
  entire `onLifeCycleOnPause` and `onLifeCycleOnStop` run, but narrowly
  suppresses this prepared-controller cleanup only for the exact pinned
  native owner during a short confirmed/provisional external-app handoff.
  This avoids bypassing all host lifecycle bookkeeping.
- `LayerHostMediaLayout.setCanPlayBackground(boolean)` writes
  `VideoPatchLayout.canPlayBackground`, used by native `playInternal()`.
  The beta now sets this permission during `Application.onActivityPrePaused`,
  **before** the host's lifecycle pause; it restores the observed original
  value on foreground return or in-app navigation.
- The new `BackgroundTransitionState` treats Home, recents and direct
  app switching as the same pending `ActivityPrePaused` transaction,
  independent of optional `onUserLeaveHint`. Actual background is
  confirmed separately with `ActivityManager.getMyMemoryState` and
  `Activity.onStop` evidence. The host `onStop` hook proceeds before
  module notification to preserve natural dispatch order.
- After a verified native lifecycle pause, fall back only to the matching
  original `VideoContext.play()`; never call bare `TTVideoEngine.play()`
  as a normal app-switch recovery, because that can resume audio without
  advancing the full-series business controller.

This is **not** a synthetic Android HOME key event. Sending HOME would
navigate to the launcher instead of keeping the destination app in
foreground; calling `onUserLeaveHint` directly would not recreate real
system lifecycle/importance transitions.

Required device regression: Home / recent-app switch / direct launch of
another app / internal feed-to-series navigation / user pause / reaching
episode EOF. Examine `completion_stage`, `host_listener`,
`prepared_controller_release`, `pre_pause_candidate` and
`episode_end_stall` events. Native hook presence and Android Debug build
must still be confirmed by CI and a real device.

## 2026-11-12 07:11 native full-series autoplay diagnosis

The uploaded `Xhongguo-diagnostic-20261112-071122.zip` confirmed that
**both** a pre-pause transition containing `onUserLeaveHint` (07:10:21)
and one without the hint (07:11:00) protected the native engine, kept
playback progress moving, received `onCompletion`, and then stalled at
EOF (`completion_but_no_next`). Merely simulating `onUserLeaveHint`
cannot fix the problem.

Forensic DEX review of the uploaded 7.3.5.32 official APK identified
the **actual full-series business callback route**, separate from
VideoShop:

```text
x05.w.onCompletion(TTVideoEngine)
  -> CopyOnWriteArrayList<WeakReference<vm4.a>> (field v)
  -> vm4.a.U1(String)
  -> com.dragon.read.component.shortvideo.impl.v2.view.adapter.a.U1(String)
  -> adapter.a.G4() (native current/next episode selection)
```

`x05.w.H()` is a concrete public getter for its native `TTVideoEngine`
(field `h`); `x05.w.v(vm4.a)` registers a listener; `x05.w.f(vm4.a)`
removes it. The host fragment's `ShortSeriesSingleFragment.onStop()`
may call `adapter.x()`, whose bytecode invokes `x05.f.pause()`.
Protecting only `TTVideoEngine.pause()` is insufficient: the business
player can still enter a paused state or lose its next-episode listener.

The beta now:
- Hooks the verified `x05.w` player and adapter callbacks for evidence
  of `onCompletion_before/after`, `U1_before/after`, `G4_before/after`,
  current business listener counts, registration/removal and pause.
- Suppresses `x05.w.pause()` and removal of the **same** adapter's
  `vm4.a` listener only while a current-engine-matched, user-pause-free,
  provisional/confirmed Android app-leave handoff is active.
- Treats actual x05.w as the preferred recovery target:
  `x05.w.resume()`, not an unrelated `VideoContext.play()` or bare
  `TTVideoEngine.play()`. Every candidate is matched to the pinned
  native engine first.
- Changes the old 10-minute blanket TTVideoEngine pause suppression to a
  short app-leave window. Crucially, marks native completion **before**
  the host callback executes so a nested U1/G4 autoplay transition is never
  mistaken for an Android lifecycle pause. The event is idempotent when
  the separate callback observer later sees the same native completion.
- Limits VideoShop prepared-controller and pause guards to a context or
  controller whose `getVideoEngine()` actually owns the current engine,
  rather than holding a stale VideoContext (the export recorded
  `getLayerHostMediaLayout() == null`, `VideoContext.isPlaying() == false`).

Expected next diagnostics on the phone:
`series_native_callback stage=onCompletion_before` and listener counts;
`series_adapter method=U1`, then `method=G4`; and a later
`TTVideoEngine.play` / new video ID. If U1 never appears despite
registered listeners, inspect the exact x05.w observer list; if U1 appears
but G4 declines to play, inspect adapter navigation state. No synthetic
episode number/URL or forced next-episode command has been added.

Full Gradle Debug build and real-device autoplay must still be tested.
The original `main` branch is unaffected.

## 2026-11-12 07:41 real x05.w EOF reaches adapter but does not advance

Source: uploaded `Xhongguo-diagnostic-20261112-074103.zip` and
offline DEX disassembly of official `红果免费短剧(1).apk`.

The new beta hooks are installed (`x05.w/adapter hooks=8` in this
reproduction). 07:40:27/28: `ShortSeriesActivity` left for background,
`x05.w.pause` was held, and background progress kept moving.
07:40:57: exact native `x05.w.onCompletion` fired, and the host
`adapter.a.U1(String)` entered and returned successfully. 07:41:02:
`completion_but_no_next` was reported. No next `TTVideoEngine.play`
was observed.

**Correction to earlier inference:** the APK's `adapter.a.U1()` calls
`G4()` only behind `J3() != 1` and `b4()` guards. `G4()` plays the
CURRENT holder (it does not choose the next episode). Missing G4 does
not prove a broken autoplay callback. Also the earlier `listeners=3`
log was tied to a previous video engine; `listeners=2` at EOF is not
proof that a listener disappeared during background.

APK findings:
- The live `ShortSeriesSingleFragment.nh()` returns `adapter.f`,
  a subclass of `adapter.a`. The old exact-class listener checks
  incorrectly reported `seriesAdapters=0` and would skip protecting
  the real subclass listener.
- `x05.w.onCompletion` first calls `x05.e.h2(x05.f)`, when set.
  `m15.a.h2` forwards through a non-null `fm4.a` callback.
  This is independent of the `vm4.a.U1` observer fanout.
- `adapter.a` has `D` (current index), `k` (native `ViewPager2`)
  and inherited `gp4.f.h` (actual episode data). A native, explicit
  viewpager item transition is therefore possible WITHOUT guessing
  the next episode's URL or calling G4 on the wrong item.

Beta changes:
1. Match native series listeners by the adapter.a superclass rather
   than exact runtime class; add live listener type and holder callback
   availability diagnostics.
2. Observe `m15.a.h2` both before and after original host dispatch.
   Record the real `U1` branch conditions (J3 and b4) as diagnostics.
3. Keep native completion/episode switching unmodified by default.
   ONLY when background playback stalls at true EOF for 5.5 seconds,
   the same engine is still active, the same ShortSeriesActivity still
   owns playback, no new play occurred, and no media pause was requested,
   look up the official adapter's loaded episode count, current index
   and native ViewPager2 index. If the indexes match AND a loaded next
   episode exists, request exactly one `ViewPager2.setCurrentItem(+1)`
   via the host's own UI navigation. No synthetic URL, engine, G4
   call or repeated replay. On the last episode or ambiguous data,
   skip with a reason.
4. Resolve inherited adapter fields by superclass traversal (the
   runtime object is adapter.f, not adapter.a).

Expected next-device logging includes
`series_native_callback` listener classes/holder callback readiness,
`series_holder_completion`, `U1 条件 probe`, and
`series_pager_recovery` as `requested`, `degraded` or `fail`.
The `requested` outcome means the host pager was instructed, NOT that
a new episode definitively played: verify a real new engine/play event.

The bounded recovery policy was checked with a local Kotlin compilation
and 5 basic guard assertions. Full Android Debug CI and on-device
behavior remain unverified. The `main` branch was not changed.

## 2026-11-12 08:01:09 background EOF: native pager ahead of business selection

Reproduction from user ZIP `Xhongguo-diagnostic-20261112-080109.zip`
(official app 7.3.5.32, beta):

- At **08:00:20**, `ShortSeriesActivity` left for the background.
  The original `x05.w.pause()` was held during the short handoff.
- At **08:00:23**, native playback had advanced, confirming working
  background transport.
- At **08:00:53**, `x05.w.onCompletion()` fired, followed by
  `m15.a.h2()` and native adapter `U1(String)`.
- At **08:00:58**, `completion_but_no_next` fired and the previous
  fallback reported `current=1 pager=2 available=11 eofNear=true`,
  `reason=safety_guard,indexAligned=false,hasNext=true`.

This is a **business selection lag**, not an invitation to move the pager
again: official `ViewPager2.currentItem` is **already index 2**, while
`adapter.a.D` remains **index 1**. A blind
`setCurrentItem(D+1)` may be a no-op, and a blind
`setCurrentItem(pager+1)` skips a real episode.

Verified by inspecting official APK DEX (`classes4.dex`):
- `adapter.a.D` is `currentSelectPosition`, while
  `adapter.a.k` is `ViewPager2`.
- `adapter.f.r4(int)` is public and overrides
  `adapter.a.r4(int)`; the base calls `s4(int)`.
- `adapter.a.s4(int)` writes the new selection into
  `D`, handles the previous holder, and runs the native page-selected
  pipeline; `G4()` plays the current holder, it does NOT select a new
  episode.
- `adapter.f.U1(String)` has additional outer autoplay logic after
  its `super.U1(String)`; tracing only the base `U1` was incomplete.

Changes in beta:
- The bounded `SeriesAutoplayRecoveryPolicy` now differentiates
  `ADVANCE_PAGER` for `D == pager`, and
  `RECONCILE_SELECTION` for exactly `pager == D+1`.
  Both still require native completion, a stable 5–18-second EOF window,
  the same engine and Activity, loaded next item, and an unchanged user
  pause epoch. Larger or unclear index differences are rejected.
- For the **observed case** `D=1,pager=2`, the module calls the
  original public `adapter.f.r4(2)` exactly ONCE and records whether
  `D` updated. It does **not** advance ViewPager2 again, simulate a
  HOME press, call G4 directly, or guess episode URLs.
- Adds optional entry/exit diagnostics for
  `adapter.f.U1(String)`, `adapter.f.r4(int)`,
  `adapter.f.g()` (scroll-next) and `adapter.a.s4(int)`,
  with the business index, actual pager index, and native page visibility
  `j15.d.y4()`. The logs will identify any hidden-page gate that still
  blocks resuming the selected episode.

A local Kotlin compilation ran 9 pure-policy assertions, including the
`D=1,pager=2` reconciliation and no-skip behavior. Full Android Debug
CI and real-device playback are not yet confirmed; only `beta` changed.

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
