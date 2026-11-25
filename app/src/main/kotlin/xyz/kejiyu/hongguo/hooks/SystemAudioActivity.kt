package xyz.kejiyu.hongguo.hooks

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Optional system-framework scope. Read-only audio playback callback, no
 * AudioService/PlaybackActivityMonitor method interception or audio mutation.
 * Only boolean state leaves system_server; no other app identity is sent.
 * A signature permission on the receiver authenticates the sender.
 */
internal object SystemAudioActivity {
    const val ACTION = "xyz.kejiyu.hongguo.SYSTEM_AUDIO_ACTIVITY"
    const val SENDER_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"
    private val packages = listOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)
    private var installed = false
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private val previous = HashMap<String, Pair<Boolean, Boolean>>()

    fun install() {
        try {
            val handler = Handler(Looper.getMainLooper())
            // At system-server-starting the Context or AudioService may not
            // yet exist. Bounded, delayed registration; never block boot.
            fun attempt(remaining: Int) {
                if (installed) return
                try {
                    val thread = Class.forName("android.app.ActivityThread")
                    val current = thread.getDeclaredMethod("currentActivityThread").invoke(null)
                        ?: error("system context not ready")
                    val context = thread.getDeclaredMethod("getSystemContext").invoke(current) as Context
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        ?: error("audio service not ready")
                    val uidMethod = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
                    val activeMethod = AudioPlaybackConfiguration::class.java.getMethod("isActive")
                    fun publish(configs: List<AudioPlaybackConfiguration>) {
                        try {
                            val active = configs.mapNotNull { config ->
                                if (activeMethod.invoke(config) != true) return@mapNotNull null
                                val usage = config.audioAttributes.usage
                                if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME)
                                    return@mapNotNull null
                                val uid = uidMethod.invoke(config) as? Int ?: return@mapNotNull null
                                if (uid < 0) null else uid
                            }.toSet()
                            // System-user packages only; do not cross user/profile boundaries.
                            for (pkg in packages) {
                                @Suppress("DEPRECATION")
                                val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                                    ?: continue
                                val own = uid in active
                                val other = active.any { it != uid && it / 100000 == uid / 100000 }
                                val state = other to own
                                if (previous[pkg] == state) continue
                                previous[pkg] = state
                                context.sendBroadcast(Intent(ACTION).setPackage(pkg)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                                    .putExtra("external", other).putExtra("own", own))
                            }
                        } catch (e: Throwable) { Log.w("XhongguoAudio", "Audio snapshot skipped", e) }
                    }
                    val cb = object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                            publish(configs)
                        }
                    }
                    audio.registerAudioPlaybackCallback(cb, handler)
                    callback = cb
                    installed = true
                    publish(audio.activePlaybackConfigurations)
                    Log.i("XhongguoAudio", "Read-only system audio activity listener registered")
                } catch (e: Throwable) {
                    if (remaining > 0) handler.postDelayed({ attempt(remaining - 1) }, 5000L)
                    else Log.w("XhongguoAudio", "Optional system audio activity unavailable", e)
                }
            }
            handler.postDelayed({ attempt(12) }, 15000L)
        } catch (e: Throwable) { Log.w("XhongguoAudio", "Optional audio activity setup skipped", e) }
    }
}
