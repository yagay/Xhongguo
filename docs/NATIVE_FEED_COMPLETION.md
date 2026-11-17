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

## Direct J2 evidence and handoff observation

The user-supplied official APK `classes4.dex` method table resolves:

`adapter.e.J2(Z, String, Bundle, Z, Integer, Boolean, Boolean): void`

The existing `p5` method has an `auto_next_episode` branch
(`m15.l.U4 -> adapter.a.P0/G4`) and `auto_to_single` branch
(`adapter.a.M0 -> ... -> ThreadUtils.postInForeground`). The
second path correlates with the reported screen takeover. A business
handoff is not equivalent to a completed new engine playback.

`NativePlayerHandoff` hooks the precise *original* J2 instance method,
records `feature=native_handoff stage=j2_enter/j2_exit` with monotonic
call IDs, Boolean argument flags, presence of an options Bundle,
background importance and original call duration. It calls
`chain.proceed()` exactly once and never exposes raw strings,
Bundle contents, identifiers or titles. The Activity launch is neither
blocked nor modified.

Correlate `feed_native original_decision`, `native_handoff j2_enter`,
`foreground activity_launch`, `native_handoff j2_exit`, and
`playback v3_progress confirmed`. `J2` does not prove shared-engine
reuse by itself: after collecting one complete trace, inspect the
actual tryShareCurrentPlayer callback and owner handoff before
replacing the host UI-dependent transition.

This is a diagnostic refactor milestone, not yet a background playback
fix: the host can still foreground its Activity when it chooses
`auto_to_single`. Do not merge this branch as the non-foreground fix.

## Verified background state mismatch and scoped V3 correction

Reopened actual uploaded diagnostic ZIPs:
- `Xhongguo-diagnostic-20261117-221720.zip` contains the
  22:17:44.294 `v3_eof background=false` event, followed by
  22:17:44.312 `feed_native series_f background=true importance=300`.
  At 22:17:44.332 `s4(target=2)` returned false while
  business D was 1 and pager was 2, so no native selection was accepted.
- `Xhongguo-diagnostic-20261117-223039.zip` includes that older
  process log and a newer 22:30 native J2 handoff. The newer log does
  not independently show another failed post-series EOF.

The original `SystemMediaBridge.isOfficialSeriesBackground()` required
the internally requested-background flag, even when Android reported
the host process importance 300 with no Activity focus. It now accepts
a confirmed Android process-background importance as an alternate
authoritative signal **only** when the current Activity is the official
ShortSeriesActivity, it has no focus, no explicit user command is
active and the activity is not finishing. The provisional in-app
media-background guard remains for preStop timing. This does not
change feed-to-series Activity launching.

`NativeSeriesV3.beginVisibilityScope()` now emits a targeted reason
(`not_background` or `state_gate`) when a completed native EOF
cannot acquire the strictly-scoped original s4 visibility exception.
It does NOT fabricate s4 success, seek, move ViewPager2 or start
another player. A successful scope still requires the same adapter,
confirmed EOF, next index, pager alignment and valid range.

Next test: from a *foreground* official Series page, switch to another
app before episode EOF. In `TRACE_EVENTS.log`, look for
`playback v3_scope` and compare with `v3_select`,
`v3_g4_gate`, `v3_progress`. If the scope activates and the
host still returns false, inspect the original adapter controller and
further APK guard conditions before making any playback mutation.

Note: no no-foreground feed-to-series handoff has been demonstrated;
the official J2 path may still open ShortSeriesActivity over another
app. This branch is experimental and not a confirmed full fix.
