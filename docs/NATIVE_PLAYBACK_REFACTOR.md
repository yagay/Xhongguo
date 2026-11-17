# Native playback core refactor (7.3.5.32)

This branch is based on beta, which is 184 commits ahead of main. It deliberately
does NOT merge into main or beta. Existing UI/settings and pre-existing background
protections are retained while media/episode consumers migrate to one native port.

## Why

The original beta let several independent call sites infer a series episode
from raw ViewPager2 position, Presenter data, cached fragments, native completion
and TTVideoEngine state. The media skip action also manually changed ViewPager2,
then attempted a delayed r4 reconciliation. These are not equivalent to the
official adapter's own business controls and can produce stale episode numbers,
wrong playback ownership or a page that changes without a playing engine.

## Architecture

- **NativeSeriesPort.kt** is the sole version-specific gateway for reading the
  official adapter's D/k/h selection, l0() SaasVideoData, and invoking A1()/g().
  It verifies ten critical APK method signatures on module startup and emits
  missing symbols to normal diagnostic logs. There is NO ViewPager2 mutation,
  blind r4 replay, guessed episode URL or own playback decoder.
- **NativePlaybackCoordinator.kt** is the process-local authoritative lifecycle:
  IDLE / PLAYING / PAUSED / COMPLETED / SELECTED / BOUND. It requires a real
  s4() acceptance, holder-engine association, and actual TTVideoEngine.play()
  before publishing the next playing episode. Handles play before x05.w.L()
  after-hook, a holder bound during original s4(), delayed holder registration
  and duplicate selection callbacks. Explicit pause invalidates pending handoff.
- **SystemMediaBridge.kt** feeds native completion, s4 selection, holder binding,
  engine play/pause and user pause events to that coordinator. MediaSession and
  notification previous/next controls now call ONLY NativeSeriesPort.skip().
  A selected-model metadata source is exposed only for a confirmed playing
  engine/holder/adapter, not a preloaded or stale previous episode.
- **FullSeriesMediaInfo.kt** uses that verified model as an authoritative
  episode source when a complete video-ID-matched list is not available.
- **Hooks.kt** gets critical obfuscated owner class names from NativeSeriesPort,
  reports method-contract compatibility and relays original APK events. It does
  not implement any new episode navigation.

The old never-called pager-recovery function was deleted. Shared engine-pause
protection and the existing tightly scoped s4 page-visibility exception are
currently retained, to avoid regressing background playback before device
traces confirm the new core can replace those conditions. A legacy bounded
native x05.w continuation is now gated through the coordinator; it is NOT an
independent route to advance an additional episode.

## Regression cases

1. Open full series, play episode N; verify media card title, episode,
   duration, seek and previous/play/next actions.
2. Click previous and next: one host A1()/g() call; observe actual s4 acceptance
   and new engine play; repeated taps never double advance.
3. Home, Recents and direct app switch before EOF; autoplay into N+1 with
   correct metadata. Ensure no stale engine play or duplicate r4.
4. Explicit pause from notification: background protection must not override it.
5. Final episode: no out-of-range navigation or replay of completed holder.
6. Switch drama in same Activity and confirm old title/poster never reappear.
7. Export the diagnostic ZIP and inspect native_hook_contract,
   native_episode_selected, native_episode_verified,
   native_playback_state, native_completion and episode_skip events.
8. :app:assembleDebug and :app:testDebugUnitTest; no Release build.

## Validation limitations

The branch has not been exercised against a running official APK inside this
environment. Bytecode/reflective method presence cannot establish that a hook
will be hit or that the native host accepted a command. Android CI status and
real-device diagnostic ZIP must be checked before merging or removing remaining
legacy background compatibility guards.
