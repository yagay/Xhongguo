#!/usr/bin/env python3
"""Static invariants for original-host-first playback (no Gradle required)."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
hooks = (root / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/Hooks.kt").read_text()
v3 = (root / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/NativeSeriesV3.kt").read_text()
bridge = (root / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/SystemMediaBridge.kt").read_text()
transport = (root / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/NativeTransportCoordinator.kt").read_text()

checks = {
    "single universal transport pause arbitration":
        hooks.count("NativeTransportCoordinator.shouldHoldOriginalPause(") == 1,
    "original host source hook is retained":
        '"setVideoModel"' in hooks and "NativeSeriesV3.onHostVideoModel(" in hooks,
    "no module-driven no-surface/early resume":
        all(token not in v3 for token in ("enableNoSurface(", "SurfaceLease(", "original.invoke(holder)")),
    "host original listener removal is never bypassed":
        "NativeSeriesV3.shouldRetainCompletionListener(" not in hooks,
    "automatic EOF is never granted offscreen override":
        "val manual = manualSkipRequest ?: return false" in v3 and
        "if (!manualRequest) return original" in v3,
    "manual native next/previous remain":
        'val entry = if (delta > 0) "g" else "A1"' in v3,
    "media session remains responsible for both transport actions":
        "override fun onSkipToPrevious()" in bridge and "override fun onSkipToNext()" in bridge,
    "only lifecycle pauses are eligible to be suppressed":
        "SystemMediaBridge.shouldSuppressBackgroundEnginePause(engine)" in transport,
}
for description, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + description)
if not all(checks.values()):
    raise SystemExit(1)
