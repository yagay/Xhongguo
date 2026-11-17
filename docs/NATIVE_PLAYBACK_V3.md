# Xhongguo native playback V3 — upstream-aware rebuild

Scope: replace only the media/short-series playback integration.
Base branch: user beta, to preserve all established UI, settings, logs, cover art,
phone controls and other enhancements. The upstream KEJIYUNB/hongguo main
provides the base Xposed module architecture but **does not implement**
SystemMediaBridge, x05.w completion, or an independent MediaSession background
player. An upstream hard reset would remove those user requirements.

## Evidence and why V2 failed

Evidence:
- 7.3.5.32 APK original DEX: classes4.dex, adapter.a.s4(int), adapter.a.G4(),
  adapter.f.U1(String) and abstract interface j15.d.i9()/y4().
- User log 2026-11-17 20:00:49: native EOF -> f.U1 entered ->
  original s4(2) returned false, adapter D=1, ViewPager2 page=2.
  There was NO G4/new engine startup. The host page moved first.
- V2 prematurely demanded ViewPager2.page == adapter.D BEFORE original s4.
  This rejected the legitimate page-ahead order before its scope could
  handle it.
- 20:00:30 diagnostic: `V2 i9 native gate: IllegalArgumentException`.
  j15.d is an abstract interface in the DEX; hooking its abstract i9
  method is invalid. Install only concrete runtime implementations.

## Architecture

`NativeSeriesV3State` — pure, single per-process reducer of:
`IDLE -> COMPLETED -> SELECTED -> BOUND -> STARTING -> PLAYING`.
A native `play()` request is not evidence of playback.
Only an actual TTVideoEngine duration plus monotonically increasing
position proves playback. User pause, route reset, timeout and onError
invalidate the pending operation.

`NativeSeriesV3` — isolated host API adapter:
- Observe BEFORE original `x05.w.onCompletion(engine)`.
- Observe host `adapter.f.U1(String)`, `adapter.a.s4(int)`,
  `adapter.a.G4()` and current adapter identity.
- Allow `s4(next)` visibility compatibility only for EXACT original EOF,
  same controller and matching adapter with either `pager == D` or
  `pager == next`. Do not manipulate the pager.
- Resolve concrete `j15.d.i9()/y4()` via adapter's own controller
  runtime class; reject abstract methods. Observe the original i9 return;
  only the original G4 associated with a genuinely accepted EOF selection
  may temporarily clear a background-specific auto-start block. The host
  can call G4 *after* s4 returns, so this grant is bound to the exact
  adapter/controller/generation, expires after 2.5 seconds, and is consumed
  by the first original G4. It is never permanent.
- Match next native x05.w holder, original listener and engine identity.
- Issue at most one original x05.w.resume() for the accepted holder with
  a verified absent Surface, using the host's c15.d.i flag; restore the
  original setting on success, pause, error, route exit, or timeout.
- MediaSession previous/next use one official g()/A1() command.
- No synthetic EOF, decoder, URL, ViewPager2.setCurrentItem(), timed
  r4(), retries, or permanent visibility override.

`SystemMediaBridge` — retains Home VideoShop support, native metadata,
artwork, Android MediaSession and notification controls; delegates series
state and transport to NativeSeriesV3. Stale beta pager-retry code removed.

`SinglePlaybackOwner` — original Home behavior retained. Only an
identified series holder may bypass a stale FEED engine play block;
ownership changes only after actual series video progress.

## Debug evidence in exported ZIP

Filter `feature/TRACE_EVENTS.log` and main hook log for:
`v3_eof`, `v3_autoplay`, `v3_scope`, `v3_visibility`,
`v3_select`, `v3_g4`, `v3_g4_gate`, `v3_holder`,
`v3_resume`, `v3_ready`, `v3_startup`, `v3_error`,
`v3_progress`. The healthy order ends in `v3_progress confirmed`.
A `v3_resume requested` or TTVideoEngine.play is only a request.

## Regression matrix before merge

1. Foreground first episode playback and automatic next.
2. Home-key background EOF and next episode.
3. Direct switch to another app, EOF and next episode.
4. Screen lock before EOF, then actual next video progress/audio.
5. 2+ consecutive episodes without double-advance.
6. Manual media previous/next at most one native selection.
7. Explicit pause must never resume because of a delayed callback.
8. A new series, missing episode, end of series, and source failure.
9. FEED <-> SERIES <-> DETAILS route changes and mini-player.
10. Media title, current episode, total, poster and progress accuracy.
11. No SystemUI crash, no excessive Root/LSPosed hook invocations.
12. Compare to beta/main and validate old settings unchanged.

Do NOT merge until those on-device tests are verified. This is an isolated
draft; main, beta, and V2 remain untouched.

## Single-path simplification (2026-11-17)

The standalone `refactor/official-playback-single-path` branch removes two
competing recovery paths from NativeSeriesV3:

- EOF/U1 no longer schedules a second `adapter.s4(next)` 160 ms later when
  `ViewPager2` is ahead of business index `D`. That disagreement is reported
  as diagnostic evidence instead of being treated as permission to navigate.
- The Android MediaSession skip action calls one official `g()/A1()` command.
  If the host has not settled its selection yet, return
  `native_selection_pending`; never follow with synthetic `s4()`.
- The only remaining background continuation is the existing one-shot,
  exact-holder official `x05.w.resume()` with a scoped no-Surface lease.
  Actual playback is confirmed by increasing native engine progress, not
  by a pager index, command return value, or MediaSession metadata.
- A scoped visibility exception is limited to the host's EOF flow, not a
  separate manual-skip retry path. Host-owned navigation is never fabricated.

This removes competing writers; it does **not** prove that the official APK
will start the next episode after a rejected `s4()`. A failed native handoff
must remain visible as `v3_handoff pending` / `v3_selection degraded` rather
than being hidden by a second transition. Validate the regression matrix on
an actual device, particularly two successive background EOF handoffs,
before promoting this branch. Only Debug builds should be used.

## Metadata binding from the confirmed host selection

The 2026-11-17 21:02 diagnostic shows confirmed native video progress
(`v3_progress index=2,3`) while media resolution repeatedly reports
`series-video-unverified`, even after official series detail was observed
(`title=true, poster=true, total=true`). The engine video-ID namespace
cannot always be joined to the Presenter SaasVideoData `vid`.

`NativeSeriesV3.confirmedSelectedSeriesModel(engine)` now exposes a
read-only host `adapter.l0()` model only when the engine is independently
confirmed progressing and both native selection `D` and ViewPager2 index
agree. `FullSeriesMediaInfo` uses that model only if its series ID has a
matching official detail snapshot. This is an additional **identity source**
inside the existing unified metadata resolver, not a new playback controller,
timer, UI scrape or unconditional cached-title fallback. The old/new drama
guard remains in place.

Validate real-device title, poster and total on episodes 1 through 4 and on
the first episode of a different drama; verify the last drama never leaks into
the new MediaSession. The index-to-ordinal conversion remains conservative:
when the complete playlist is not verified, do not guess the current episode.

## Unexpected foreground task investigation

The 21:02:15 diagnostic ZIP shows the original FEED -> SERIES navigation,
followed by confirmed app background and multiple SERIES engine handoffs.
It does NOT contain a system-level ActivityTaskManager attribution of the
reported foreground takeover. Do not suppress all launches based on this
correlation.

`ForegroundLaunchTrace` now observes, without modifying return values:
- Activity.startActivity/startActivityForResult and ContextImpl.startActivity;
- ActivityManager.moveTaskToFront requests in the host process;
- Activity.onNewIntent, onPause, onResume and focus-gained callbacks.

All observations use `XH_TRACE feature=foreground` and should appear
in existing hook logs and feature/TRACE_EVENTS.log exported to diagnostic
ZIP. No intent extras, raw URLs or entire stacks are exported; only the
destination category, numeric flags, process importance and up to three
host/module method names. Reproduce with a different app in front, then
collect logs immediately after the actual takeover. Correlate
`task_to_front` / `context_launch` / `activity_launch` with
`activity_resume` and `window_focus`. A focus event without a host-side
launch indicates that the request may originate from the framework or
another process; use privileged ActivityTaskManager system logs to attribute
that case.

No playback, transition, MediaSession or automatic episode code was changed
by this diagnostic-only instrumentation. Test build and live host behavior
remain unverified until CI/device execution.

## Background feed EOF foreground-stealing regression (2026-11-17 21:38)

Confirmed from `Xhongguo-diagnostic-20261117-213855.zip`:
- 21:38:12.459: MainFragmentActivity paused.
- 21:38:13.394: importance=300, stopped=true; another app foreground.
- 21:38:46.692: real `x05.w.onCompletion` and V3 EOF.
- 21:38:46.747: `ShortSeriesImpl.openShortSeriesActivityForResult`
  called `Activity.startActivityForResult` for ShortSeriesActivity with
  importance=300 and no window focus.
- 21:38:46.897: ShortSeriesActivity resumed; 21:38:48.561 gained focus.

The concrete origin is the host's FEED EOF navigation, not module
`moveTaskToFront`. The module's post-launch lifecycle owner handoff
comes *after* the unwanted Activity start.

The guarded Activity launch interception now suppresses only:
a stopped background MainFragmentActivity, a confirmed native EOF within
2.5 seconds, an exact ShortSeriesActivity Intent, and a call stack containing
the official ShortSeriesImpl.openShortSeriesActivityForResult. The original
user-driven foreground navigation and the already-open series episode
autoplay route are not changed. Structured log:
`feature=foreground stage=background_eof_series_launch result=blocked`.

**Trade-off:** On some official feed routes, launching series Activity is
how the host starts the next item. Suppression can prevent that continuation;
the next test must check background continuity as well as no foreground
takeover. Do not attempt to "fix" continuity by scheduling a second Activity
launch or moving the task to front.

Debug build and device validation are still required.

## Regression after direct Activity suppression (2026-11-17 21:45)

The 21:45:17 diagnostic proves that the earlier direct suppression
reached `foreground/background_eof_series_launch blocked` at 21:45:09.205,
then `v3_selection degraded host_did_not_accept_next_after_eof` about
4.2 seconds later. The original official Activity launch is part of the
host's feed-to-series playback handoff. Dropping it prevents continuation.

The direct short-circuit was **removed**. In its place, the exact verified
background feed EOF launch passes the original
`Activity.startActivityForResult(Intent, requestCode, Bundle)` call
through with AOSP ActivityOptions `android.activity.avoidMoveToFront=true`
while preserving the host's Intent and original options. The normal
2-argument overload still delegates to Android's 3-argument overload.
The single-use event log is now
`feature=foreground stage=background_eof_series_launch result=requested`.

This is an **experimental task-placement approach**: the option is a
hidden system behavior and must be verified on OxygenOS 16. Depending on
task lifecycle, the host may not initialize a new engine until its
Activity actually resumes, even if Activity creation succeeds. To test,
verify 1) no `window_focus` for the series during another app's use,
2) an accepted host selection, 3) a distinct engine and increasing
native position. Never treat `result=requested` alone as proof of
continuation or non-foreground launch. If step 2/3 fail, the actual
next task is to implement a host-authoritative feed player transition,
not to reintroduce background Activity force-start.
