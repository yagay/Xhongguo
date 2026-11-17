# Xhongguo — Official native short-series playback V2

Base: `beta` (not a wholesale rollback to `main`). Existing UI, settings,
mini-player and Home VideoShop behavior are retained. This is an isolated draft
branch; no changes to `main`, `beta` or the former
`refactor/native-playback-core` branch.

## Source of truth (host APK 7.3.5.32)

| Layer | Native entry | Behavior |
|---|---|---|
| End of episode | `x05.w.onCompletion(TTVideoEngine)` | Record true EOF BEFORE calling original |
| Autoplay business | `adapter.f.U1(String)` | Observe the host's original autoplay |
| Selection | `adapter.a.s4(int)` | Never add a second pager move or retry `r4()` |
| Start decision | `adapter.a.G4()` / `j15.d.i9()` | Observe actual i9 return; narrowly relax it only inside verified background EOF s4/G4 |
| Holder / engine | `x05.w.v(vm4.a)` and `x05.w.L(engine)` | Bind the exact host next holder |
| Source | `x05.w.onPrepared`, state/error callback | Readiness/error observation only |
| Playing | monotonic `TTVideoEngine` duration + position | The sole V2 proof of a playing next episode |

## Policy

- Preserve the original host's business control and its single TTVideoEngine.
- Allow no-Surface **host** `x05.w.resume()` at most once for the exact
  selected next holder after the original s4 stack has returned.
- Keep the host's `c15.d.i` flag only while the genuine next video is preparing;
  restore when real progress is confirmed, the user pauses, an error occurs,
  the host route changes, or the safety timeout expires. No synthetic
  Surface, no source injection and no raw `TTVideoEngine.play()`.
- Do not call native business-policy `i9()` or `I4()` a second time from
  diagnostics. Observe the return value during the original invocation.
- On MediaSession next/previous, invoke exactly one native `f.g()` /
  inherited `a.A1()` and verify the host selection; do not call ViewPager2
  before/after or schedule a delayed `r4()`.
- Defer `SinglePlaybackOwner.onEnginePlayed` for the new **series** engine
  until its own progress increases. Prevent FEED ownership from vetoing the
  legitimate SERIES start before that confirmation.
- Retain only the exact adapter completion listener during a verified
  original background leave, not indefinitely across route changes.
- A failure is reported in logs; it must not trigger repeated page changes
  or a second decoder.

## Diagnostics

Filter the exported ZIP for `v2_eof`, `v2_autoplay`, `v2_visibility`,
`v2_g4`, `v2_i9`, `v2_selection`, `v2_holder`,
`v2_resume`, `v2_ready`, `v2_start`, `v2_error`,
and `v2_progress`. `v2_start=requested` does **not** mean playback
has been confirmed; only `v2_progress=confirmed` does.

## Regression matrix before merge

1. Full-series foreground automatic next episode (including normal video).
2. Home-key background next episode, with media metadata following the new title.
3. Direct app-switch background next episode.
4. Screen locked before EOF, then new episode truly playing.
5. Next/previous buttons in the system media notification, exactly one index.
6. Manual pause is never reversed by delayed callbacks.
7. Final episode / playlist boundary does not loop or jump twice.
8. Feed -> full-series -> details -> feed route changes, mini-player continuity.
9. Retry/error network conditions: report readiness/error, no recursive skip.
10. Compare before/after native callback coverage and exported logs.

## Rollback

This branch is independent. The stable `beta` branch remains unmodified.
Do not merge the draft PR until those on-device scenarios pass.
