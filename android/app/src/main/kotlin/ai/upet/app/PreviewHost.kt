package ai.upet.app

import ai.upet.core.EngineState
import ai.upet.core.FeatureContent
import ai.upet.vehicle.FakeVehicleAdapter
import ai.upet.vehicle.PleosVehicleAdapter

/** Neutral bridge; only the debug source set provides a control panel. */
internal interface PreviewHost {
    val source: FakeVehicleAdapter?
    val realSource: PleosVehicleAdapter?
    val state: EngineState
    val available: Boolean
    val fuelDemoEnabled: Boolean
    val growth: Int
    val diagnosticAvailable: Boolean
    val features: List<FeatureContent>
    val selectedId: String
    val labels: Map<String, ContentCopy.SignalLabels>
    val places: List<ContentCopy.Place>
    val platformAllowed: Boolean
    fun change(action: (FakeVehicleAdapter) -> Unit)
    fun choose(id: String)
    fun place(id: String)
    fun platform()
    fun observe(label: String)
    fun routeDiagnostic()
    fun close()
}
