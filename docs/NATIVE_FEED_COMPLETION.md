# Hongguo 7.3.5.32: Native feed EOF decision (2026-11-17)

## Evidence and reproducible problem

Source: user-supplied `红果免费短剧_7.3.5.32.apk`, DEX
`classes4.dex`, and `Xhongguo-diagnostic-20261117-215148.zip`.

The 21:51 log confirms a background EOF at 21:51:42.361,
then a native `ShortSeriesImpl.openShortSeriesActivityForResult` request
from `adapter.e.p5` at 21:51:42.394 (importance=300, focused=false).
With `avoidMoveToFront`, the host requested the Activity launch but no
new Series playback was confirmed; at 21:51:46.561 the V3 policy reported
`host_did_not_accept_next_after_eof`.

This indicates an **original host policy transition**, not a missing
timer or a failure of the Xhongguo MediaSession. The Activity launch
path also owns native series player initialization. Dropping the launch,
or altering task placement without creating a runnable player, does not
supply a replacement.

## Actual APK method references (not guessed Java)

- `adapter.e.U1(String)` delegates to `adapter.a.U1`, observes
  completion conditions, and calls static `adapter.e.p5(adapter.e)`.
- `adapter.e.p5(adapter.e)` has two different downstream routes:
  `m15.l.U4(SaasVideoDetailModel, boolean)` plus `adapter.a.P0/G4`
  for `auto_next_episode`, and `adapter.a.M0()` plus inner-flow
  work for `auto_to_single`. The latter is tied to the observed
  `ShortSeriesImpl` fullscreen launch.
- `adapter.f.U1(String)` is a distinct native policy. It tests
  `p35.b.v0(Context)` and contains explicit official `听模式后台完播`
  / `听模式后台剧末完播` branches. Its native
  `adapter.f.r5(int, adapter.f)` uses `ViewPager2.setCurrentItem`.
- These are separate host subclasses. `f.r5` is **not** a safe
  substitute for `e.p5`, nor is globally forcing the listen-mode
  predicate safe.

## Architectural next step implemented here

The `refactor/native-feed-completion` branch **removes** the two
experimental Activity behavior changes: interception by returning
`null` and `avoidMoveToFront`. Normal host Activity calls are now
unchanged. The existing `ForegroundLaunchTrace` remains read-only.

A new `NativeFeedCompletion` class and precise native hooks observe
original, unmodified host decisions in `e.U1`, `e.p5`, `a.M0`,
`m15.l.U4`, `f.U1`, `p35.b.v0`, and `f.r5`.
Events appear as `XH_TRACE feature=feed_native` in the existing
diagnostic ZIP. This is policy instrumentation, not speculative
auto-advance or a second player. Existing V3 full-series ownership,
metadata and verified progress continue untouched.

Test in a Debug build: a feed episode with a next episode, feed final
episode with an inner-series transition, and full-series EOF. In each
case reproduce both foreground and background and compare the
`original_decision` with confirmed engine-progress events.

**Important:** This branch does **not yet** solve no-foreground
continuation for the native `auto_to_single` route. A true fix requires
proof of a host video-data/engine handoff without an Activity becoming
foreground. Until that pathway is identified, replacing the host
decision with `f.r5`, synthetic swipes, forced `s4`, or a duplicate
decoder would be unsafe and reintroduce the previously observed
playback regression.

No Release build or release publication should be triggered.
