package ai.upet.vehicle

/** No android.car types cross this boundary; unavailable restrictions keep interaction locked. */
internal interface PlatformUxGate {
    fun connect()
    fun disconnect()
}

internal fun requireCarUxClasses(loader: ClassLoader) {
    listOf("android.car.Car", "android.car.drivingstate.CarUxRestrictions",
        "android.car.drivingstate.CarUxRestrictionsManager").forEach {
        Class.forName(it, false, loader)
    }
}

internal class OptionalPlatformGate(
    private val create: ((UxState) -> Unit) -> PlatformUxGate,
    private val log: (String, String) -> Unit,
    private val changed: (UxState) -> Unit,
) : PlatformUxGate {
    private var generation = 0L
    private var delegate: PlatformUxGate? = null

    override fun connect() {
        disconnect()
        val token = ++generation
        changed(UxState.PENDING)
        try {
            delegate = create { value -> if (token == generation) changed(value) }
            delegate?.connect()
        } catch (error: Exception) { fail(error) }
        catch (error: LinkageError) { fail(error) }
    }

    private fun fail(error: Throwable) {
        diagnostic { log("CarUxRestrictions.stack", error.stackTraceToString()) }
        log("CarUxRestrictions", "unavailable=android.car type=" + error.javaClass.simpleName)
        disconnect()
        changed(UxState.UNAVAILABLE)
    }

    override fun disconnect() {
        generation++
        changed(UxState.CLOSED)
        val previous = delegate
        delegate = null
        try { previous?.disconnect() }
        catch (error: Exception) { cleanupFailure(error) }
        catch (error: LinkageError) { cleanupFailure(error) }
    }

    private fun cleanupFailure(error: Throwable) {
        log("CarUxRestrictions.disconnect", "failed type=" + error.javaClass.simpleName)
    }
}
