# Hongguo 7.3.5.32: native background player handoff

## Goal / boundaries

Goal: when the official feed reaches the end while the user is in another
app, load the actual NEXT episode with the host's native data and engine
**without moving Hongguo's task to foreground**.

The baseline `e0a4208` was user-confirmed to play consecutive series
episodes. It is preserved on `refactor/native-feed-completion` (restored).
The experimental foreground diagnostics and their failed launch policies
are kept in `backup/native-feed-before-autoplay-restore-20261117`.

This branch inherits the verified original V3 player and makes **no
ActivityOptions, task-stack, media engine or EOF control changes**. It
does not yet fix the foreground launch. Do not merge as a fix.

## Actual APK evidence (classes4.dex, not guessed methods)

1. `adapter.e.U1(String)` is the original feed completion policy.
   Its terminal branch logs `合集完播进内流，暂停播放`, calls
   `adapter.a.x()` (pause), then `adapter.e.p5(e)`.
2. `adapter.e.p5(e)` chooses `auto_next_episode`
   (`m15.l.U4(...)`, `a.P0(...)`, `a.G4()`) when original
   current series detail has the next entry. For `auto_to_single`,
   it builds the original source bundle and invokes `a.M0()`
   for the selected holder before passing the business launch along.
3. `adapter.e.J2(boolean, String, Bundle, boolean, Integer, Boolean,
   Boolean): void` constructs `ShortSeriesLaunchArgs`, checks current
   `x05.f` playback and a VID match, writes `share_current_player`,
   calls `ShortSeriesApi.openShortSeriesActivityForResult(args)`, then
   continues its original navigation/player cleanup. Calling `J2`
   without the Activity does NOT substitute a source or complete EOF.
4. `adapter.a.g():boolean` is a pager operation. It uses
   `ViewPager2.setCurrentItem` and `adapter.a.r4`. It is NOT a
   standalone headless next-video method.
5. The host has a real native player source loader:
   `x05.w.c0(SaasVideoData,boolean):void` (startPlay). Its code
   checks the host engine, available VideoModel, then calls
   `x05.w.N(SaasVideoData,Consumer,boolean)` for asynchronous
   source loading and `x05.w.O(...)` / `x05.w.M(VideoModel)`
   for native binding. This is a genuine potential headless transport,
   but needs a VERIFIED next `SaasVideoData` and correct lifecycle
   ownership. Passing a stale/current entry would replay the same video.
6. `ShortSeriesLaunchArgs` defines
   `insertVideoData: InsertVideoData`,
   `firstVideoId`, `targetVid`, `seriesId`,
   `isAutoEnter` and `isListenMode`.
   `InsertVideoData.videoData` is a wrapper with
   `getVideoData(): SaasVideoData`.
   These are original host DTOs; their presence does NOT prove that the
   embedded video is the next episode.

## New data boundary: actual source identity, not UI callbacks

`NativeHeadlessPreflight` hooks only the official
`adapter.e.U1(String)` scope and
`ShortSeriesApi.openShortSeriesActivityForResult(ShortSeriesLaunchArgs)`.
It compares the active selected holder `M0().getBoundData().mVideoData`
with `args.insertVideoData.videoData.getVideoData()`.

`NativeNextEpisodePolicy` accepts a candidate only if both videos have
nonempty series/video IDs, they belong to the SAME series, their video IDs
differ and `nextIndex == currentIndex + 1`. Repeated, different-series,
unknown and skipped candidates are strictly rejected. IDs, URLs and
account fields are never logged.

The preflight outputs only:
`XH_TRACE feature=headless stage=native_source_preflight`, including
`identity=...` and `verified=true|false`.

## Why this is not yet the final implementation

Previous attempts:
- Cancel original `startActivityForResult`: no foreground takeover,
  but host didn't build the new player.
- Allow original Activity with `avoidMoveToFront`: avoided takeover,
  but no confirmed new playback.
- Force additional `resume()` on the next holder: state/selection can
  advance while `TTVideoEngine` has no actual loaded source.
- Force `adapter.a.g()`: requires a ViewPager and its business holder,
  and can advance an index without loading a native engine.

Neither launch cancellation nor task reordering is a source loader.

**Next executable decision:** inspect one actual preflight ZIP. If
`verified=true`, build a source-owned headless controller around the
original `x05.w.c0 -> N -> O` pipeline, verifying `VideoModel`
assignment and engine progress before taking ownership. Also account
for `J2`'s original post-launch cleanup. If `identity` is
`MISSING_IDENTITY` or `SAME_VIDEO`, the official launch args do not
contain a usable next episode, so the correct architecture requires
calling the official series-detail/data-center loader first. Do not
silently change to an unrelated next drama or fake an accepted episode.

The existing user-verified playback baseline is intentionally unchanged.
All new runtime probes are read-only; background foreground stealing can
therefore still occur in this experimental branch.
