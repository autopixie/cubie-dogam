package ai.upet.vehicle

/** Read-only callback transport; tests can schedule replies without Android or a vehicle service. */
internal interface ReadSession {
    val closing: Boolean
    fun <T> read(name: String, invoke: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        complete: (T) -> Unit, failed: (Exception) -> Unit = {})
    fun subscribe(name: String, register: () -> Unit, unregister: () -> Unit,
        complete: (Boolean) -> Unit = {})
    fun unsubscribe(name: String)
    fun deliver(name: String, finalized: () -> Unit = {}, action: () -> Unit)
    fun failure(name: String, error: Exception)
}
