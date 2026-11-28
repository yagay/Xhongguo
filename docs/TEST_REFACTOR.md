# Test branch stability refactor

- PlaybackGate combines overlapping external audio, call, focus and manual hold blockers. Recovery tickets remain bound to a verified engine, video ID, session generation and user-action epoch.
- External audio recovery requires a fresh system playback snapshot and confirms real playback before completing, with bounded retries.
- UI scanning is coalesced by layout events with a two-second fallback and only runs on a focused activity.
- Artwork requests share in-flight work by URL, and stale subscriptions are cancelled.
- Both settings surfaces have search and dependencies; standalone settings supports portable JSON backup and restore.
- Test builds use the repository's Actions channel rather than stable upstream releases.
- Hot reload is deliberately rejected until teardown of host/system-process resources has been validated on device. Restart the target app; reboot after updating the system-framework scope.

## Validation
CI builds Debug, runs JVM regression tests for playback gates, video identity and settings metadata, and checks module namespace and packaged LSPosed resources. These tests do not replace real-device checks of ColorOS lifecycle, system_server audio callbacks, episode navigation, artwork and native playback.
