package ai.upet.vehicle

/** Values are supplied by the app from labelled evidence, never inferred from bounds or enum ordinals. */
data class VehiclePolicy(
    val parkingSource: ParkingSource = ParkingSource.SDK_GEAR_A,
    val heaterEvidence: HeaterEvidence? = null,
    val hvacAutoBooleanVerified: Boolean = false,
    val airCleanerBooleanVerified: Boolean = false,
    val odometerKmPerRaw: Double? = null,
    val allowGearFallbackWithoutUx: Boolean = false,
    /** Only the dogam collector may verify live changes without a preselected practice. */
    val collectPassiveChanges: Boolean = false,
) {
    enum class ParkingSource { SDK_GEAR_A }
    init {
        require(!allowGearFallbackWithoutUx) { "Interaction requires usable UX restrictions" }
        require(heaterEvidence == null || heaterEvidence.valid)
        require(odometerKmPerRaw == null || odometerKmPerRaw.isFinite() && odometerKmPerRaw > 0)
    }
}
