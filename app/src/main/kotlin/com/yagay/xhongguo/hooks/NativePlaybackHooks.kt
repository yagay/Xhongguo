package com.yagay.xhongguo.hooks

import android.app.Activity
import com.yagay.xhongguo.LogUtil
import com.yagay.xhongguo.MainHook
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

/** Verified native transport binding. Original host invocation always occurs at most once. */
internal object NativePlaybackHooks {
    @Volatile var installedTransportCount = 0
        private set
    fun install(module: MainHook, loader: ClassLoader, universal: Boolean,
                foreground: () -> Activity?,
                publish: (Any, String, Activity?) -> Unit,
                observe: (Any?, String, Activity?) -> Unit) {
        val engine = NativeHookContract.load(loader, NativeHookContract.ENGINE)
        val transports = NativeHookContract.methods(engine, NativeHookContract.engineTransport)
        val audit = HookBindingAudit()
        var installed = 0
        for ((index, method) in transports.withIndex()) {
            if (audit.attempt("native-transport", method.toGenericString()) {
                module.hook(method).setId("xhSystemMediaTTVideoEngine_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val candidate = chain.thisObject
                        val activity = foreground()
                        val blocked = when (method.name) {
                            "play", "start" -> FeatureFaultGuard.test("native-start-gate") {
                                AudioFocusCoordinator.blocksNativeBackgroundStart(candidate, activity)
                            }
                            "pause" -> FeatureFaultGuard.test("continuity-pause") {
                                InAppPlaybackContinuity.keepLifecyclePause(candidate)
                            } || FeatureFaultGuard.test("mini-pause") {
                                InAppMiniPlayer.shouldKeepPlaying(candidate)
                            } || FeatureFaultGuard.test("background-pause") {
                                SystemMediaBridge.shouldSuppressBackgroundEnginePause(candidate)
                            }
                            else -> false
                        }
                        if (blocked) return@Hooker null
                        if (method.name == "pause") FeatureFaultGuard.run("native-pause-intent") {
                            AudioFocusCoordinator.observeNativePauseIntent(candidate, activity)
                        }
                        val result = chain.proceed()
                        FeatureFaultGuard.run("native-transport-observer") {
                            if (universal) observe(candidate, method.name, activity)
                            else if (candidate != null && method.name !in setOf("start", "release"))
                                publish(candidate, method.name, activity)
                        }
                        result
                    })
            }) installed++
        }
        // The APK has these actual native callbacks; use them to confirm a
        // requested/current engine, never adopt an unrelated preload decoder.
        if (universal) {
            val controller = NativeHookContract.load(loader, NativeHookContract.CONTROLLER)
            for ((index, method) in NativeHookContract.methods(controller,
                    NativeHookContract.controllerState).withIndex()) {
                audit.attempt("native-state-confirmation", method.toGenericString()) {
                    module.hook(method).setId("xhVerifiedNativeState_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            FeatureFaultGuard.run("native-state-confirmation") {
                                val candidate = chain.getArg(0)
                                if (NativePlaybackSession.boundEngine(chain.thisObject) === candidate)
                                    NativePlaybackSession.observeVerifiedCallback(candidate, method.name,
                                        foreground, publish)
                            }
                            result
                        })
                }
            }
        }
        installedTransportCount = installed
        LogUtil.info("  ✓ system media TTVideoEngine transport observers: $installed/" + transports.size)
        audit.report()
    }
}
