package ai.upet.vehicle

import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.upet.core.Tpms

/** Missing/conflicting readings are unknown. A displayed warning is not a pressure measurement. */
internal fun readTpms(value: Telltales?): Tpms? {
    val states = value?.component1()?.filter { it.component1() == TelltaleType.TPMS }
        ?.map { it.component2() }?.distinct()
    return when (states?.singleOrNull()) {
        TelltaleState.OFF -> Tpms.Off
        TelltaleState.ON -> Tpms.On
        TelltaleState.BLINK -> Tpms.Blink
        else -> null
    }
}
