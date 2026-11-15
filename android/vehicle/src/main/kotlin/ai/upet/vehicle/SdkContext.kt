package ai.upet.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/** API28-compatible SDK-only connection dispatch. UI/CarUx contexts are unchanged. */
internal class SdkContext(base: Context, private val connectionsQueue: SdkCallQueue,
    private val log: (String, String) -> Unit, private val failed: (Exception) -> Unit) : ContextWrapper(base) {
    private val connections = ConcurrentHashMap<ServiceConnection, Connection>()
    @Volatile private var acceptingConnections = true
    override fun getApplicationContext(): Context = this

    private inner class Connection(val original: ServiceConnection) : ServiceConnection {
        @Volatile var active = true
        private fun deliver(name: String, action: () -> Unit) {
            if (!acceptingConnections || !active) return
            log("SdkConnection." + name + ".queued", "from=" + Thread.currentThread().name)
            connectionsQueue.post {
                if (acceptingConnections && active && connections[original] === this) {
                    log("SdkConnection." + name, "thread=" + Thread.currentThread().name)
                    try { action() } catch (e: Exception) {
                        log("SdkConnection." + name, "failed type=" + e.javaClass.simpleName)
                        failed(e)
                    }
                }
            }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) =
            deliver("connected") { original.onServiceConnected(name, service) }
        override fun onServiceDisconnected(name: ComponentName) =
            deliver("disconnected") { original.onServiceDisconnected(name) }
        override fun onBindingDied(name: ComponentName) =
            deliver("bindingDied") { original.onBindingDied(name) }
        override fun onNullBinding(name: ComponentName) =
            deliver("nullBinding") { original.onNullBinding(name) }
    }

    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        log("SdkConnection.bind.requested", "thread=" + Thread.currentThread().name + " flags=" + flags)
        if (!acceptingConnections) {
            log("SdkConnection.bind.result", "bound=false deliveryClosed=true")
            return false
        }
        val proxy = connections.computeIfAbsent(connection) { Connection(it) }
        return try {
            super.bindService(intent, proxy, flags).also { bound ->
                log("SdkConnection.bind.result", "bound=" + bound + " thread=" + Thread.currentThread().name)
                if (!bound) { proxy.active = false; connections.remove(connection, proxy) }
            }
        } catch (e: Exception) {
            proxy.active = false; connections.remove(connection, proxy)
            log("SdkConnection.bind.result", "failed type=" + e.javaClass.simpleName)
            throw e
        }
    }

    /** Stop SDK callback entry, but retain proxies so release can perform normal unbind. */
    fun freezeConnections() {
        acceptingConnections = false
        connections.values.forEach { it.active = false }
    }

    override fun unbindService(connection: ServiceConnection) {
        val proxy = connections.remove(connection) ?: return
        proxy.active = false
        super.unbindService(proxy)
    }
}
