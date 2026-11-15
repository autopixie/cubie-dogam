package ai.upet.core

import kotlinx.serialization.json.*

data class FeatureContent(
    val id: String, val name: String, val basic: Boolean, val area: String,
    val description: String, val instruction: String,
)

data class PetRule(
    val id: String, val expression: String, val lines: List<String>, val growth: Int,
    val meaning: String? = null, val action: String? = null,
)

/** The four bundled JSON files are byte-for-byte copies of the web prototype inputs. */
data class ContentBundle private constructor(
    val features: List<FeatureContent>,
    val rules: Map<String, PetRule>,
    val placeIds: Set<String>,
    val basicTarget: Int,
    val sources: Map<String, JsonObject>,
) {
    val fuelDemoEnabled: Boolean get() = sources.getValue("pet_rules").getValue("policy").jsonObject["fuelDemoEnabled"]?.jsonPrimitive?.booleanOrNull == true

    companion object {
        val names: List<String> = listOf("features", "pet_rules", "memories", "places")

        fun fromResources(): ContentBundle = fromJson(names.associateWith { name ->
            requireNotNull(ContentBundle::class.java.getResourceAsStream("/content/${name}_v0.json")) {
                "Missing content resource: $name"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        })

        fun fromJson(texts: Map<String, String>): ContentBundle {
            require(texts.keys == names.toSet()) { "Exactly four content files are required" }
            val source = texts.mapValues { Json.parseToJsonElement(it.value).jsonObject }
            source.forEach { (name, value) -> require(value.int("schemaVersion") == 1) { "$name schema" } }
            val features = source.getValue("features").array("features").map { raw ->
                val f = raw.jsonObject
                FeatureContent(f.string("id"), f.string("name"), f.bool("basic"),
                    f.getValue("signal").jsonObject.string("area"), f.string("description"), f.string("instruction"))
            }
            require(features.isNotEmpty() && features.map { it.id }.distinct().size == features.size)
            require(features.all { it.id.isNotBlank() && it.area.isNotBlank() })
            val ids = features.map { it.id }.toSet()
            val rawRules = source.getValue("pet_rules").array("events").map { it.jsonObject }
            require(rawRules.map { it.string("id") }.distinct().size == rawRules.size)
            rawRules.forEach { rule ->
                require(rule.array("featureIds").all { it.jsonPrimitive.content in ids }) { "Unknown feature in rule" }
            }
            val rules = rawRules.associate { r ->
                val rule = PetRule(r.string("id"), r.string("expression"),
                    r.array("lines").map { it.jsonPrimitive.content }, r.int("growth"),
                    r["meaning"]?.jsonPrimitive?.contentOrNull, r["action"]?.jsonPrimitive?.contentOrNull)
                require(rule.lines.isNotEmpty())
                require(rule.growth == if (rule.id == "success") 1 else 0) { "Only first feature success grows" }
                rule.id to rule
            }
            val required = setOf("welcome", "waiting", "success", "already_on", "repeat", "locked", "parking", "tpms_on", "tpms_blink", "fuel_increase", "place", "companion")
            require(rules.keys.containsAll(required))
            require(rawRules.single { it.string("id") == "success" }.array("featureIds").map { it.jsonPrimitive.content }.toSet() == ids)
            for (id in listOf("tpms_on", "tpms_blink")) {
                require(rules.getValue(id).expression == "concerned")
                require(!rules.getValue(id).meaning.isNullOrBlank() && !rules.getValue(id).action.isNullOrBlank())
            }
            val policy = source.getValue("pet_rules").getValue("policy").jsonObject
            listOf("explicitStart", "initialInactive", "firstRewardOnly", "noBackgroundObservationAssumption", "noDeath", "fuelDemoOnly").forEach {
                require(policy.bool(it)) { "Required policy: $it" }
            }
            val target = source.getValue("pet_rules").getValue("stage").jsonObject.int("basicTarget")
            require(target > 0)
            val places = source.getValue("places")
            require(places.string("origin") == "demo" && !places.bool("defaultEnabled"))
            require(!source.getValue("memories").getValue("storage").jsonObject.bool("defaultEnabled"))
            return ContentBundle(features, rules, places.array("templates").map { it.jsonObject.string("id") }.toSet(), target, source)
        }
    }
}

internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
internal fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
internal fun JsonObject.array(key: String) = getValue(key).jsonArray
