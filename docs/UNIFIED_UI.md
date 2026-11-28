# Unified settings UI

Standalone launch now shows Function / Status / Tools navigation. The injected panel uses the same SettingsSurface, schema and preferences, with framework Views rather than loading Material library classes into the target. Standalone theme uses Material3 and MaterialSwitch.

- Theme, density, dynamic colors; fixed headers; grouped rows; mode radio selection; search, aliases, recent queries, row navigation and highlighting.
- Configuration edits use allowlisted atomic provider batches. No old preference keys renamed; old Activity remains a compatibility entry. Favorites and recent searches remain local to each UI host.
- Status is based on timestamped observations from the primary target process, not an assertion that LSPosed/target is currently running. Root is checked only on request. No external app identity or video/account metadata stored.
- Root tools select an installed target and require confirmation before stop/restart. Backup is validated and previewed before restore.
- Removed upstream panel watermark and unused duplicate panel editors. No changes to native player transport policy. In-app mini player control appearance updated only.

Device tests needed: dark theme, system bars, rotation, small windows, font scale, back navigation, input keyboard, switch persistence and provider sync, root and diagnostic flows, LSPosed host panel compatibility.
