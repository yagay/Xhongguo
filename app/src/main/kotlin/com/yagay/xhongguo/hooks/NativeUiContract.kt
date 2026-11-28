package com.yagay.xhongguo.hooks

import android.view.View

/** APK-confirmed VideoShop seek bar hierarchy. No guessed resource IDs. */
internal object NativeUiContract {
    private val seekBars = setOf(
        "com.ss.android.videoshop.commonbase.widget.SeekBarForNewUI",
        "com.ss.android.videoshop.layer.widget.SSSeekBar",
        "com.ss.android.videoshop.layer.progressbar.SSProgressBar"
    )
    fun isVideoSeekBar(view: View): Boolean =
        seekBars.any { NativeMetadataAccess.extendsType(view.javaClass, it) }
}
