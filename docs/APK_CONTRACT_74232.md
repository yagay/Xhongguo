# CN 7.4.2.32 APK-grounded hook contract

Reference: user's uploaded 7.4.2.32 APK, package com.phoenix.read, versionCode 74232. The manifest and all 25 DEX definitions were inspected; 330,983 class definitions. SHA-256 is stored in tools/apk_contract_74232.json. The APK itself is not committed.

The reference contract includes 64 method signatures, 9 typed fields and additional class checks. Run:

    python3 tools/audit_target_apk.py /path/to/hongguo.apk --strict-reference

For a later APK omit --strict-reference; missing contracts are reported, not automatically mapped to another method.

## What changed
- Native SDK transport moved into NativePlaybackHooks. Exact argument/return types and static/instance qualification precede registration.
- VideoController render/state events confirm only the bound pending/current engine. Native state is read again; arbitrary preloading engines cannot be adopted from these callbacks alone.
- DTO, PlayEntity, resolution, custom progress bars, RecyclerView binding and bottom navigation use APK-confirmed semantic signatures or class hierarchies.
- LegacyTargetProfiles holds the previous exact-version mappings. The current and unknown APKs use an independent AUTO profile.
- Runtime capabilities report signature presence rather than asserting feature success.

## Unverified routes
The previous short-holder mappings dw4.t and cy4.t are absent from this APK; nt4.t exists with unrelated superclass and is not evidence of compatibility. Some feature routes (particularly double-tap comment dispatch, legacy OLED/toolbar/membership-specific mappings) remain unverified; the universal path does not borrow their old obfuscated identifiers. The inspected APK is CN, not a new international APK. Functional ColorOS testing is still required.
