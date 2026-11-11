#!/usr/bin/env python3
"""Fail CI if AUTO starts silently inheriting an old Hongguo profile.

This is a source contract check, not an on-device functional test.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
targets = (ROOT / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/TargetNames.kt").read_text()
hooks = (ROOT / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/Hooks.kt").read_text()
capabilities = (ROOT / "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/RuntimeCapabilities.kt").read_text()

auto = re.search(
    r"private val AUTO_TEMPLATE\s*=\s*Names\((.*?)\n    \)",
    targets,
    re.DOTALL,
)
assert auto is not None, "No independent universal template"
auto_text = auto.group(1)
old_profile_fields = [
    "shortHolder", "holderBaseS1", "shortStateMethod", "shortMaskMethod",
    "shortControlsMethod", "shortConfigMethod", "shortLayoutResetMethod",
    "shortLandscapeMethod", "shortMaskField", "shortNativeClearField",
    "shortCleanManagerField", "homeFragmentMaskMethod", "homeFragmentMaskField",
    "seriesFragmentRefreshMethod", "seriesPagerGetter", "seriesHolderGetter",
    "fixedToolbarShowMethod", "customizeToolbarShowMethod",
    "customizeToolbarApplyMethod", "toolbarBase", "progressBar", "hideView1",
    "hideView2", "oledBright", "oledBrightAction", "topZoneTouch",
    "playbackState", "adVideoEndShowMethod", "pauseAdEntryClass",
    "pauseAdEntryMethod", "resolutionController", "resolutionEngineField",
    "rightViewAgency", "rightViewAgencyEventMethod", "kmpVipModel",
]
for field in old_profile_fields:
    assert re.search(r"\b" + field + r'\s*=\s*""', auto_text), (
        f"Universal profile must not inherit old {field}"
    )
for field in (
    "seriesLayoutFields", "doubleTapHandlers", "resolutionModelMethods",
    "kmpAcctService", "hideIdNames", "progressIdNames",
):
    assert re.search(r"\b" + field + r"\s*=\s*emptyList\(\)", auto_text), (
        f"Universal list must start empty: {field}"
    )
selector = targets.split("fun namesFor(", 1)[1].split("fun isSupported(", 1)[0]
assert "return AUTO_TEMPLATE.copy(" in selector
assert "return byVersion ?:" not in selector
assert not re.search(r"Class\.forName\(CN_73\d+\.", selector), (
    "Never discover legacy profiles by probing classes from other versions"
)
assert 'TargetNames.isUniversal(gNames)' in hooks, "Missing universal-mode guard"
assert "RuntimeCapabilities.inspect(classLoader, pkg, detected.first)" in hooks
assert "fun inspect(loader: ClassLoader" in capabilities

print("PASS: AUTO profile is independent; no legacy-version fallback")
