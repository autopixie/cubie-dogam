package ai.upet.app

import ai.upet.core.EnginePolicy
import ai.upet.vehicle.VehiclePolicy

/** Measurements and provisional product choices are collected here. */
internal object ProductPolicy {
    const val dogamEnabled = true
    const val questionAnswerEntryEnabled = false
    const val assistedHeatEntryEnabled = false
    const val conversationSpeechEnabled = false
    // Console/SDK Off/On observed 2026-11-13; legacy practice defaults remain separate.
    val dogamVehicle: VehiclePolicy get() = vehicle.copy(collectPassiveChanges = true,
        hvacAutoBooleanVerified = true, airCleanerBooleanVerified = true)
    val dogamExtraVehicle = ai.upet.vehicle.DogamVehiclePolicy(
        seatHeat = ai.upet.vehicle.DogamStageEvidence(-3, 3, 0, setOf(1)),
        frontDefog = ai.upet.vehicle.DogamVehiclePolicy.FrontDefog.DEFROSTER_FRONT_WINDSHIELD,
    ) // Seat ventilation and mirror On values are still unverified.
    val courses by lazy { ai.upet.core.LearningCourses.fromResources() }
    val basicFeatureIds: Set<String> get() = courses.firstSubmissionPracticeIds
    const val tpmsUnknownWorried = false
    // Measurement and submission require usable, unrestricted UX.
    const val gearFallbackWhenUxUnavailable = false

    // No measured current-temperature meaning/unit/threshold/freshness yet. Never invent a default.
    val k1 = ai.upet.core.K1Policy(null, null, null, null)
    // A reconnect epoch is not a stable vehicle identity. Real automatic proposals remain blocked.
    val verifiedVehicleScope: String? = null

    // Gear A can lock the UI; confirmed P alone never authorizes interaction.
    val vehicle = VehiclePolicy(
        parkingSource = VehiclePolicy.ParkingSource.SDK_GEAR_A,
        heaterEvidence = ai.upet.vehicle.HeaterEvidence(0, 3, 0, 1), // Console Off/Low observed 2026-11-01; exact bounds only.
        hvacAutoBooleanVerified = false,        // Awaiting Console Off/On against the SDK Boolean.
        airCleanerBooleanVerified = false,      // Awaiting Console Off/On against the SDK Boolean.
        odometerKmPerRaw = null,                // Until measured, diaries say distance unknown.
        allowGearFallbackWithoutUx = gearFallbackWhenUxUnavailable,
    )

    fun engine(demo: Boolean, content: ai.upet.core.ContentBundle) = EnginePolicy(
        basicFeatureIds = basicFeatureIds,
        requireAllBasic = true,
        enabledPracticeIds = basicFeatureIds,
        recordOrigin = if (demo) "demo" else "vehicle",
        deferParkingSnapshot = !demo,
        allowFuelDemo = demo && content.fuelDemoEnabled,
    )

    fun evolution(awarded: List<String>): Int = when (basicFeatureIds.count { it in awarded }) {
        0 -> 1
        basicFeatureIds.size -> 3
        else -> 2
    }
}
