package ai.upet.core

/** Product choices are supplied by the app; default behavior keeps the original web parity. */
data class EnginePolicy(
    val basicFeatureIds: Set<String>? = null,
    val requireAllBasic: Boolean = false,
    val recordOrigin: String = "demo",
    val deferParkingSnapshot: Boolean = false,
    val allowFuelDemo: Boolean? = null,
    val enabledPracticeIds: Set<String>? = null,
) {
    init {
        require(recordOrigin in setOf("demo", "vehicle"))
        require(basicFeatureIds == null || basicFeatureIds.isNotEmpty())
    }
}
