package ai.upet.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler

/** Public AAOS restriction integration required by the platform guide. */
internal class DrivingRestrictions(
    private val context: Context,
    private val main: Handler,
    private val log: (String, String) -> Unit,
    private val changed: (UxState) -> Unit,
) : PlatformUxGate {
    private var generation = 0L
    private var listenerGeneration = 0L
    private var ready = false
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    override fun connect() {
        changed(UxState.PENDING)
        val token = ++generation
        try {
            log("CarUxRestrictions.connect", "call")
            val connection = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { readyCar, connected ->
                if (token != generation) return@createCar
                car = readyCar
                clearManager()
                if (!connected) {
                    log("CarUxRestrictions.lifecycle", "ready=false")
                    changed(UxState.UNAVAILABLE)
                } else {
                    try {
                        val active = readyCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        manager = active
                        ready = active != null
                        if (active == null) changed(UxState.UNAVAILABLE)
                        else {
                            val listenerToken = ++listenerGeneration
                            val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { value ->
                                if (ready && listenerToken == listenerGeneration && token == generation) {
                                    try { accept(value, "callback") }
                                    catch (e: Exception) { fail(e) }
                                    catch (e: LinkageError) { fail(e) }
                                }
                            }
                            active.registerListener(listener)
                            log("CarUxRestrictions.registerListener", "complete")
                            accept(active.currentCarUxRestrictions, "get")
                        }
                    } catch (e: Exception) { fail(e) }
                    catch (e: LinkageError) { fail(e) }
                }
            }
            car = connection
        } catch (e: Exception) { fail(e) }
        catch (e: LinkageError) { fail(e) }
    }
    private fun clearManager() {
        ready = false
        listenerGeneration++
        val previous = manager
        manager = null
        try { previous?.unregisterListener() }
        catch (e: Exception) { log("CarUxRestrictions.unregisterListener", "failed type=" + e.javaClass.simpleName) }
        catch (e: LinkageError) { log("CarUxRestrictions.unregisterListener", "failed type=" + e.javaClass.simpleName) }
    }
    private fun accept(value: CarUxRestrictions?, source: String) {
        if (!ready) return
        val requires = value?.isRequiresDistractionOptimization
        val restrictions = value?.activeRestrictions
        val allowed = requires == false && restrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        log("CarUxRestrictions." + source, "requires=" + requires + " active=" + restrictions + " allowed=" + allowed)
        changed(if (requires == null || restrictions == null) UxState.UNKNOWN
            else if (allowed) UxState.ALLOWED else UxState.RESTRICTED)
    }
    private fun fail(error: Throwable) {
        // Retain this connection lifetime for UX recovery; clearManager invalidates old listeners.
        clearManager()
        diagnostic { log("CarUxRestrictions.stack", error.stackTraceToString()) }
        log("CarUxRestrictions", "failed type=" + error.javaClass.simpleName)
        changed(UxState.UNAVAILABLE)
    }
    override fun disconnect() {
        changed(UxState.CLOSED)
        generation++
        clearManager()
        log("CarUxRestrictions.unregisterListener", "complete")
        try { car?.disconnect(); log("CarUxRestrictions.disconnect", "complete") }
        catch (e: Exception) { log("CarUxRestrictions.disconnect", "failed type=" + e.javaClass.simpleName) }
        catch (e: LinkageError) { log("CarUxRestrictions.disconnect", "failed type=" + e.javaClass.simpleName) }
        car = null
    }
}
