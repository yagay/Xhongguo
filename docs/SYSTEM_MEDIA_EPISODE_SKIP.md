# 7.3.5.32 MediaSession previous/next episode fix (experimental)

## Verified evidence

The restored user-approved source baseline is commit
`e0a4208de669ae1dc7663cbc1610b45bdee0ce38`.
This patch lives ONLY on `fix/system-media-episode-skip`;
`refactor/headless-native-playback` remains identical to that baseline.

Official Hongguo 7.3.5.32 `classes4.dex` declares
- `adapter.f.g(): boolean`: next episode; invokes
  `ViewPager2.setCurrentItem(index, true)` and `adapter.f.r4(index)`.
- `adapter.a.A1(): boolean`: previous episode; invokes
  `ViewPager2.setCurrentItem(index, true)` and `adapter.a.r4(index)`.
- `adapter.a.r4(int):boolean` calls `adapter.a.s4(int):boolean`.

The original MediaSession callbacks were already installed and called
`NativeSeriesV3.skipNative(delta)`, but the latter had no explicit
manual-episode background page visibility authorization. The only
exception in `beginVisibilityScope()` required a verified *automatic*
EOF. The existing V3 EOF observer also used `x05.w.onCompletion()`
without requiring a full-series activity; feed and full series can
share the same x05.w class. That can leave the series state in
`COMPLETED` and cause a user skip to return `handoff_pending`.

## Changes

1. Keep both native calls `f.g()` and `a.A1()` as the **only**
   origin of next/previous requests. No synthetic `s4()`,
   second `ViewPager2.setCurrentItem()`, or timer playback retries.
2. A manual skip arms a strictly scoped target- and adapter-specific
   token only in **system-confirmed** background Series playback,
   expiring after 2.5 seconds. The original host's next matching
   `s4` can use the original offscreen/G4 gate exception, without
   altering automatic EOF grants.
3. Advance the V3 selected-player state only if the original `s4`
   returns true and native business index/pager match the target.
   Confirm actual playback from the new TTVideoEngine progress.
4. Restrict Series V3 EOF ownership to the official
   ShortSeriesActivity. Ignore a feed EOF in this state machine and
   do not bind an initial feed engine as the full-series owner.
5. The MediaSession/notification exposes previous or next only when
   the native Series playlist has a valid adjacent episode and its
   selection state is stable; the user cannot skip beyond an edge.
6. Add `feature=media stage=episode_skip_input` and
   `feature=playback stage=v3_manual_skip/v3_manual_scope` events
   explaining native rejection, pending selection or expiry.

## Tests

`NativeManualEpisodeSkipPolicyTest` covers next, previous, wrong
target, wrong adapter, stale generation, expired request, foreground,
explicit pause, pager mismatch and playlist boundaries. CI is
Debug-only (`:app:assembleDebug` then `:app:testDebugUnitTest`).

Test steps:
- Install Debug from the new branch and enable LSPosed scope.
- In an actual ShortSeriesActivity, press next then previous on the
  Android system media card, both in foreground and while another app
  has focus. Wait for real playback progress after each command.
- Reproduce two consecutive next presses at least 1 second apart.
- Export a diagnostic ZIP after testing.
- Check `episode_skip_input`, `episode_skip`,
  `v3_manual_skip`, `v3_scope`, `v3_select`,
  `v3_holder` and `v3_progress`.

This patch is a source-backed fix candidate; without a complete
Debug CI result and on-device playback confirmation it is NOT proven
to repair every device/playlist mode. It does not add feed-mode
previous/next or fix feed-to-series foreground takeover.
