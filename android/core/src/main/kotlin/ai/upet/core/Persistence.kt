package ai.upet.core

import kotlinx.serialization.json.*

internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
internal fun finiteKm(value: Double?) = value != null && value.isFinite() && value in 0.0..1_000_000.0
private val episodePattern = Regex("parking-\\d+-\\d+")
private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

internal fun restoreState(json: String?, content: ContentBundle, recordOrigin: String = "demo"): Pair<PersistedState, Boolean> {
    if (json == null) return PersistedState() to false
    return try {
        val raw = Json.parseToJsonElement(json).jsonObject
        require(raw["version"] == JsonPrimitive(1))
        val awarded = raw.array("awarded")
        val memories = raw.array("memories")
        val places = raw.array("places")
        val settings = raw.getValue("settings").jsonObject
        val memoryEnabled = settings["memoryEnabled"] == JsonPrimitive(true)
        val deletionEpoch = raw.safeCounter("deletionEpoch")
        val featureIds = content.features.map { it.id }.toSet()
        val validMemories = memories.mapNotNull { element -> runCatching {
            val m = element.jsonObject
            val id = m.strictString("id")
            val date = m.strictString("date")
            val status = m.strictString("distanceStatus")
            require(episodePattern.matches(id) && datePattern.matches(date))
            require(m.strictString("origin") == recordOrigin && status in setOf("valid", "unknown", "invalid"))
            val km = m.km("distanceKm")
            require(status != "valid" || km != null)
            MemoryEntry(id, date, origin = recordOrigin, distanceStatus = status, distanceKm = if (status == "valid") km else null)
        }.getOrNull() }.distinctBy { it.id }
        val seenIds = validMemories.map { it.id }.toSet()
        val validPlaces = places.mapNotNull { element -> runCatching {
            val p = element.jsonObject
            val id = p.strictString("id")
            require(id in content.placeIds && p.strictString("origin") == "demo")
            PlaceCard(id)
        }.getOrNull() }.distinctBy { it.id }
        val pending = runCatching {
            val p = raw.getValue("pendingEpisode").jsonObject
            val id = p.strictString("id")
            val epoch = p["deletionEpoch"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull
            require(memoryEnabled && episodePattern.matches(id) && epoch == deletionEpoch && id !in seenIds)
            ParkingEpisode(id, deletionEpoch, p.km("baselineKm"))
        }.getOrNull()
        PersistedState(introduced = raw["introduced"] == JsonPrimitive(true),
            awarded = awarded.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .filter { it in featureIds }.distinct(),
            memories = validMemories.takeLast(50), places = validPlaces,
            settings = Settings(memoryEnabled, settings["locationEnabled"] == JsonPrimitive(true)),
            deletionEpoch = deletionEpoch, episodeCounter = raw.safeCounter("episodeCounter"),
            pendingEpisode = pending, baselineKm = raw.km("baselineKm")) to false
    } catch (_: Exception) {
        PersistedState() to true
    }
}

internal fun serializeState(data: PersistedState): String = buildJsonObject {
    put("version", data.version); put("introduced", data.introduced)
    putJsonArray("awarded") { data.awarded.forEach { add(it) } }
    putJsonArray("memories") { data.memories.forEach { m -> add(buildJsonObject {
        put("id", m.id); put("date", m.date); put("origin", m.origin)
        put("distanceStatus", m.distanceStatus); put("distanceKm", m.distanceKm?.let(::JsonPrimitive) ?: JsonNull)
    }) } }
    putJsonArray("places") { data.places.forEach { p -> add(buildJsonObject { put("id", p.id); put("origin", p.origin) }) } }
    putJsonObject("settings") { put("memoryEnabled", data.settings.memoryEnabled); put("locationEnabled", data.settings.locationEnabled) }
    put("deletionEpoch", data.deletionEpoch); put("episodeCounter", data.episodeCounter)
    put("baselineKm", data.baselineKm?.let(::JsonPrimitive) ?: JsonNull)
    put("pendingEpisode", data.pendingEpisode?.let { e -> buildJsonObject {
        put("id", e.id); put("deletionEpoch", e.deletionEpoch)
        put("baselineKm", e.baselineKm?.let(::JsonPrimitive) ?: JsonNull)
    } } ?: JsonNull)
}.toString()

private fun JsonObject.strictString(key: String): String {
    val value = getValue(key).jsonPrimitive
    require(value.isString)
    return value.content
}
private fun JsonObject.safeCounter(key: String): Long = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_INTEGER } ?: 0
private fun JsonObject.km(key: String): Double? = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.doubleOrNull?.takeIf(::finiteKm)
