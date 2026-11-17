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
  only an original G4 call within the verified synchronous EOF selection
  can temporarily clear a background-specific auto-start block.
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
