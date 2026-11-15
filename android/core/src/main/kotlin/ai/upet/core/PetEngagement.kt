package ai.upet.core

/** Only a newly accepted vehicle result counts as input; snapshots and rejected events do not. */
object PetEngagement {
    fun vehicleResult(event: AppEvent, before: EngineState, after: EngineState): Boolean = when (event) {
        is AppEvent.Signal -> !event.snapshot && event.value == true &&
            before.session?.featureId == event.featureId && after.session == null &&
            before.signals[event.featureId]?.value == false &&
            after.signals[event.featureId]?.seq == event.seq &&
            after.signals[event.featureId]?.seq != before.signals[event.featureId]?.seq &&
            after.lastEvent in setOf("success", "repeat")
        is AppEvent.TpmsChanged -> event.value != before.tpms && event.value in setOf(Tpms.On, Tpms.Blink)
        else -> false
    }
}
