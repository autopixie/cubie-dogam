package ai.upet.core

import java.time.Instant

enum class Gear { P, R, N, D, UNKNOWN }
enum class Tpms { Off, On, Blink }

sealed interface AppEvent {
    data object Intro : AppEvent
    data class Start(val featureId: String, val area: String) : AppEvent
    data object Cancel : AppEvent
    data object Skip : AppEvent
    data class Signal(val featureId: String, val area: String, val value: Boolean?, val seq: Long,
        val epoch: Long, val snapshot: Boolean = false, val sessionId: Long? = null) : AppEvent
    data class Capability(val featureId: String, val supported: Boolean) : AppEvent
    data class GearChanged(val value: Gear, val now: Instant? = null) : AppEvent
    data class Odometer(val value: Double?) : AppEvent
    /** A fresh foreground P read, fenced against lifecycle, deletion and parking episode changes. */
    data class ParkingSnapshot(val value: Double?, val epoch: Long, val deletionEpoch: Long,
        val episodeId: String?, val now: Instant? = null) : AppEvent
    data class TpmsChanged(val value: Tpms?) : AppEvent
    /** Synthetic percentage only; this is not an actual refuelling event. */
    data class FuelDemo(val value: Double, val snapshot: Boolean = false) : AppEvent
    data class Visibility(val foreground: Boolean) : AppEvent
    data class Platform(val allowed: Boolean) : AppEvent
    data class MemorySetting(val enabled: Boolean) : AppEvent
    data class LocationSetting(val enabled: Boolean) : AppEvent
    data class PlaceDemo(val placeId: String, val deletionEpoch: Long) : AppEvent
    data object DeleteRecords : AppEvent
    /** No real location/destination events are connected in WO02. */
    data class Unhandled(val type: String) : AppEvent
}

data class Settings(val memoryEnabled: Boolean = false, val locationEnabled: Boolean = false)
data class MemoryEntry(val id: String, val date: String, val origin: String = "demo",
    val distanceStatus: String, val distanceKm: Double?)
data class PlaceCard(val id: String, val origin: String = "demo")
data class ParkingEpisode(val id: String, val deletionEpoch: Long, val baselineKm: Double?)
data class PersistedState(val version: Int = 1, val introduced: Boolean = false,
    val awarded: List<String> = emptyList(), val memories: List<MemoryEntry> = emptyList(),
    val places: List<PlaceCard> = emptyList(), val settings: Settings = Settings(),
    val deletionEpoch: Long = 0, val episodeCounter: Long = 0,
    val pendingEpisode: ParkingEpisode? = null, val baselineKm: Double? = null)
data class FeatureSignal(val value: Boolean? = null, val seq: Long = -1, val supported: Boolean = true)
data class PracticeSession(val id: Long, val featureId: String, val area: String, val startSeq: Long, val epoch: Long)
data class EngineState(val data: PersistedState, val gear: Gear, val foreground: Boolean,
    val platformAllowed: Boolean, val signals: Map<String, FeatureSignal>, val odometerKm: Double?,
    val tpms: Tpms?, val fuel: Double?, val session: PracticeSession?, val epoch: Long,
    val sessionCounter: Long, val lastEvent: String, val notice: String, val lastMemoryId: String?, val eventCount: Long)
data class Stage(val target: Int, val count: Int, val mode: String)
data class EngineView(val allowed: Boolean, val stage: Stage, val growth: Int, val rule: PetRule,
    val warning: PetRule?, val unknownWarning: Boolean, val line: String)
