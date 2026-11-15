package ai.upet.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import java.util.concurrent.atomic.AtomicBoolean

/** App state/terminal callbacks stay on main; SDK bodies and connection callbacks never do. */
internal class SdkSession(
    context: Context,
    private val main: Handler,
    private val log: (String, String) -> Unit,
    private val onClosed: (SdkSession) -> Unit,
) : ReadSession {
    private val context = context.applicationContext
    private val calls = SdkCallQueue()
    private val connectionCalls = SdkCallQueue("upet-vehicle-connection")
    private var sdkContext: SdkContext? = null
    var onFailure: (Exception) -> Unit = {}
    private var currentVehicle: Vehicle? = null
    val vehicle: Vehicle get() = checkNotNull(currentVehicle)
    @Volatile override var closing = false
        private set
    private class Subscription(val name: String, val cancel: () -> Unit) {
        @Volatile var active = true
    }
    private val subscriptions = mutableListOf<Subscription>()
    private val drain = CallbackDrain {
        log("release", "pending=0 subscriptions=0 queued")
        calls.post {
            // API calls/unregisters stay FIFO. Freeze new connection entry and let an
            // already-running SDK connection callback return before releasing Vehicle.
            sdkContext?.freezeConnections()
            connectionCalls.post {
                log("release", "connection_barrier_before_sdk complete")
                calls.post {
                    try { sdkCall("release") { currentVehicle?.release() } }
                    catch (e: Exception) { failure("release", e) }
                    finally {
                        sdkContext?.freezeConnections()
                        connectionCalls.post {
                            log("release", "connection_barrier_after_sdk complete")
                            calls.finish()
                            connectionCalls.finish()
                            main.post { onClosed(this) }
                        }
                    }
                }
            }
        }
    }

    fun initialize(complete: () -> Unit, failed: (Exception) -> Unit) {
        if (closing) return
        val done = drain.begin()
        calls.post {
            var error: Exception? = null
            if (!closing) try {
                sdkCall("initialize") {
                    val transport = SdkContext(context, connectionCalls, log, ::transportFailed)
                    sdkContext = transport
                    currentVehicle = Vehicle(transport)
                    vehicle.initialize()
                }
            } catch (e: Exception) { error = e }
            main.post {
                try {
                    if (!closing) {
                        val problem = error
                        if (problem == null) complete() else { failure("initialize", problem); failed(problem) }
                    }
                } catch (e: Exception) { failure("initialize.handler", e); if (!closing) failed(e) }
                finally { done() }
            }
        }
    }

    override fun <T> read(
        name: String,
        invoke: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        complete: (T) -> Unit,
        failed: (Exception) -> Unit,
    ) {
        if (closing) return
        val done = drain.begin()
        val once = AtomicBoolean()
        log(name, "queued pending=" + drain.pending)
        fun finish(action: () -> Unit) {
            if (!once.compareAndSet(false, true)) return
            main.post {
                try { if (!closing) action() else log(name, "discard closing") }
                catch (e: Exception) { failure(name + ".handler", e); if (!closing) failed(e) }
                finally { done() }
            }
        }
        calls.post {
            if (closing) finish {} else try {
                sdkCall(name) { invoke({ result -> finish { log(name, "complete"); complete(result) } },
                    { error -> finish { failure(name, error); failed(error) } }) }
            } catch (error: Exception) {
                finish { failure(name, error); failed(error) }
            }
        }
    }

    override fun subscribe(name: String, register: () -> Unit, unregister: () -> Unit,
        complete: (Boolean) -> Unit) {
        if (closing) { complete(false); return }
        // Retain cleanup even when SDK registration throws after a partial connection.
        val subscription = Subscription(name, unregister)
        subscriptions += subscription
        calls.post {
            var registered = false
            if (!closing && subscription.active) try {
                sdkCall(name + ".register", register)
                registered = true
            } catch (e: Exception) { failure(name, e) }
            main.post {
                if (!closing && subscription.active) try { complete(registered) }
                catch (e: Exception) { transportFailed(e) }
            }
        }
    }

    override fun unsubscribe(name: String) {
        val matches = subscriptions.filter { it.name == name }
        subscriptions.removeAll(matches.toSet())
        matches.forEach { subscription ->
            subscription.active = false
            calls.post {
                try { sdkCall(name + ".unregister", subscription.cancel) }
                catch (e: Exception) { failure(name + ".unregister", e) }
            }
        }
    }

    override fun deliver(name: String, finalized: () -> Unit, action: () -> Unit) {
        main.post {
            try {
                if (!closing) {
                    try { action() } catch (e: Exception) { failure(name, e) }
                }
            } finally { finalized() }
        }
    }

    override fun failure(name: String, error: Exception) {
        log(name, "failed code=" + (SdkFailure.code(error) ?: "UNAVAILABLE") +
            " health=" + SdkFailure.health(SdkFailure.code(error)) +
            " type=" + error::class.simpleName)
    }

    private fun transportFailed(error: Exception) {
        val abort = {
            if (!closing) {
                failure("transport", error)
                try { onFailure(error) }
                catch (e: Exception) { failure("transport.handler", e) }
                finally { close() }
            }
        }
        if (Looper.myLooper() == main.looper) abort() else main.post { abort() }
    }

    private fun sdkCall(name: String, action: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        log(name, "invoke_begin thread=" + Thread.currentThread().name + " tMs=" + System.currentTimeMillis())
        try { action() }
        finally { log(name, "invoke_end thread=" + Thread.currentThread().name +
            " elapsedMs=" + (SystemClock.elapsedRealtime() - started)) }
    }

    fun close() {
        if (closing) return
        closing = true
        subscriptions.map { it.name }.distinct().reversed().forEach(::unsubscribe)
        log("close", "deferRelease pending=" + drain.pending)
        drain.close()
    }
}
