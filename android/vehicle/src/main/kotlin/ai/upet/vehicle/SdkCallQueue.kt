package ai.upet.vehicle

import android.os.Handler
import android.os.HandlerThread

/** One serial SDK worker. Independent roles may name their queue; main never joins it. */
internal class SdkCallQueue(name: String = "upet-vehicle-io") {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)
    fun post(action: () -> Unit): Boolean = handler.post { action() }
    fun finish() { thread.quitSafely() }
}
