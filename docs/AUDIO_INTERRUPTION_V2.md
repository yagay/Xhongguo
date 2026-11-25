# Audio interruption V2

Audio focus and call-mode management are independent of the media-card preference.
Confirmed native engine observations are the shared source of truth. Android focus
loss and calls cancel continuity guards, then use bounded pause verification; no
new decoder or forced Activity launch. Native request references survive until
abandon; observable listener binding and foreground-only bounded supplemental
requests are logged. Temporary resume is tied to the same engine, video and
generation; permanent focus loss or manual pause requires user action.

Settings: focus pause and call pause on by default; call resume on, transient
focus resume off, duck interruption off, external audio activity off. The duck
option cannot override a host app's own native handling. A call accompanied by
permanent focus loss is intentionally not resumed automatically.

Optional external-media detection: explicitly enable System Framework (android)
scope in LSPosed and restart the device, then enable the advanced setting in
Xhongguo. The system process only uses a read-only AudioPlaybackCallback and
sends package-targeted booleans. No media content, app names, or audio capture.
The receiver requires MODIFY_AUDIO_ROUTING from its sender. Scope expansion is
necessary because this module uses static scope. ROMs can restrict the privileged
callback; system-user support only, no cross-profile scanning. Disable the
setting or system scope if unavailable. No promise of detection for every
non-focus player or custom audio route.

Regression: feed/full-series, background and lockscreen; phone ringtone, call,
VoIP, media apps, notifications, focus denied/reacquisition, media card off,
native engine replacement, user pause, auto-next during call; both resume toggles;
system scope off/on. Look for 音频协调 and XhongguoAudio logs. Device validation
is required; static review cannot establish real ColorOS behavior.
