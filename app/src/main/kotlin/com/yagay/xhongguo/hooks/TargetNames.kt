package com.yagay.xhongguo.hooks

object TargetNames {

    const val CN_PACKAGE = "com.phoenix.read"
    const val OVERSEA_PACKAGE = "com.phoenix.read.oversea.gp"
    val SUPPORTED_CN_VERSIONS = listOf("7.3.1.32", "7.3.2.32", "7.3.3.18", NativeHookContract.APK_VERSION)
    val SUPPORTED_OVERSEA_VERSIONS = listOf("7.3.1.32")

    data class Names(
        val profileId: String,
        val packageName: String,
        val versionName: String,

        val useLegacySeedIds: Boolean = false,

        val structuralFullscreenWatch: Boolean = false,

        val seriesToolbarProfile: String = "none",

        val shortHolder: String,
        val holderBaseS1: String,
        val shortStateMethod: String,
        val shortMaskMethod: String,
        val shortControlsMethod: String,
        val shortConfigMethod: String,
        val shortLayoutResetMethod: String,
        val shortLandscapeMethod: String,
        val shortMaskField: String,
        val shortNativeClearField: String,
        val shortCleanManagerField: String,

        val homeFragmentMaskMethod: String,
        val homeFragmentMaskField: String,
        val seriesFragmentRefreshMethod: String,
        val seriesPagerGetter: String,
        val seriesHolderGetter: String,
        val seriesLayoutFields: List<String>,
        val fixedToolbarShowMethod: String,
        val customizeToolbarShowMethod: String,
        val customizeToolbarApplyMethod: String,
        val toolbarBase: String,

        val progressBar: String,
        val hideView1: String,
        val hideView2: String,

        val oledBright: String,
        val oledBrightAction: String,

        val topZoneTouch: String,
        val playbackState: String,
        val adVideoEndShowMethod: String,

        val pauseAdEntryClass: String,
        val pauseAdEntryMethod: String,

        val resolutionController: String,
        val resolutionModelMethods: List<String>,
        val resolutionEngineField: String,

        val resolutionApplyMethod: String = "",

        val doubleTapHandlers: List<String>,
        val rightViewAgency: String,
        val rightViewAgencyEventMethod: String,

        val kmpAcctService: List<String>,
        val kmpVipModel: String,

        val hideIdNames: List<String>,
        val progressIdNames: List<String>,

        val staticHideIds: List<Int> = emptyList(),
        val staticProgressIds: List<Int> = emptyList(),

        val pauseRestoreIds: List<Int> = emptyList(),

        val doubleTapLikeView: String = "",

        val doubleTapHolderLikeMethod: String = "",
    )

    internal val CN_73132 get() = LegacyTargetProfiles.CN_73132
    internal val CN_73232 get() = LegacyTargetProfiles.CN_73232
    internal val CN_73318 get() = LegacyTargetProfiles.CN_73318
    internal val OVERSEA_73132 get() = LegacyTargetProfiles.OVERSEA_73132

    /**
     * Universal mode is deliberately EMPTY. It does not inherit a single
     * obfuscated class, method or resource ID from another release.
     * Stable framework / VideoShop / TTVideoEngine hooks are installed
     * separately and use runtime capability detection.
     */
    private val AUTO_TEMPLATE = Names(
        profileId = "AUTO",
        packageName = CN_PACKAGE,
        versionName = "",
        structuralFullscreenWatch = true,
        seriesToolbarProfile = "auto",
        shortHolder = "",
        holderBaseS1 = "",
        shortStateMethod = "",
        shortMaskMethod = "",
        shortControlsMethod = "",
        shortConfigMethod = "",
        shortLayoutResetMethod = "",
        shortLandscapeMethod = "",
        shortMaskField = "",
        shortNativeClearField = "",
        shortCleanManagerField = "",
        homeFragmentMaskMethod = "",
        homeFragmentMaskField = "",
        seriesFragmentRefreshMethod = "",
        seriesPagerGetter = "",
        seriesHolderGetter = "",
        seriesLayoutFields = emptyList(),
        fixedToolbarShowMethod = "",
        customizeToolbarShowMethod = "",
        customizeToolbarApplyMethod = "",
        toolbarBase = "",
        progressBar = "",
        hideView1 = "",
        hideView2 = "",
        oledBright = "",
        oledBrightAction = "",
        topZoneTouch = "",
        playbackState = "",
        adVideoEndShowMethod = "",
        pauseAdEntryClass = "",
        pauseAdEntryMethod = "",
        resolutionController = "",
        resolutionModelMethods = emptyList(),
        resolutionEngineField = "",
        doubleTapHandlers = emptyList(),
        rightViewAgency = "",
        rightViewAgencyEventMethod = "",
        kmpAcctService = emptyList(),
        kmpVipModel = "",
        hideIdNames = emptyList(),
        progressIdNames = emptyList(),
    )

    internal val CN: Names get() = AUTO_TEMPLATE
    internal val OVERSEA: Names get() = AUTO_TEMPLATE.copy(
        packageName = OVERSEA_PACKAGE,
    )

    fun isUniversal(names: Names): Boolean = names.profileId == "AUTO" || names.profileId.startsWith("AUTO-")

    fun namesFor(pkg: String, versionName: String?, classLoader: ClassLoader? = null): Names {
        val normalized = versionName?.trim()?.substringBefore(' ').orEmpty()
        val exact = when (pkg) {
            CN_PACKAGE -> when (normalized) {
                "7.3.1.32" -> CN_73132
                "7.3.2.32" -> CN_73232
                "7.3.3.18" -> CN_73318
                else -> null
            }
            OVERSEA_PACKAGE -> if (normalized == "7.3.1.32")
                OVERSEA_73132 else null
            else -> null
        }
        // The only permitted legacy mapping is a verified exact-version
        // match. A missing old class is NOT a license to borrow another map.
        if (exact != null) {
            if (classLoader == null) return exact
            try {
                Class.forName(exact.shortHolder, false, classLoader)
                return exact
            } catch (_: Throwable) {
                // Same versionName with a different APK build: fail closed.
            }
        }
        val region = if (pkg == OVERSEA_PACKAGE) "OVERSEA" else "CN"
        return AUTO_TEMPLATE.copy(
            profileId = "AUTO-$region-" + normalized.ifBlank { "unidentified" },
            packageName = pkg,
            versionName = normalized,
        )
    }

    fun isSupported(pkg: String, versionName: String?): Boolean {
        val v = versionName?.trim()?.substringBefore(' ') ?: return false
        return when (pkg) {
            CN_PACKAGE -> v in SUPPORTED_CN_VERSIONS
            OVERSEA_PACKAGE -> v in SUPPORTED_OVERSEA_VERSIONS
            else -> false
        }
    }
}
