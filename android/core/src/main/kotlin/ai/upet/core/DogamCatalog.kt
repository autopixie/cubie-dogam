package ai.upet.core

import kotlinx.serialization.json.*

data class DogamSource(val url: String, val section: String, val model: String)
data class DogamBack(val where: String, val how: String, val goodDay: String, val note: String, val source: DogamSource)
data class DogamQuiz(val q: String, val choices: List<String>, val answer: Int)
data class DogamSeasonVariant(val name: String, val rarity: Int, val months: List<Int>, val rule: String)
data class DogamCard(
    val no: Int, val kind: String, val id: String? = null, val name: String = "", val type: String = "",
    val rarity: Int = 0, val sdk: Map<String, String> = emptyMap(), val discover: String = "",
    val riddle: String = "", val hint2: String = "", val back: DogamBack? = null,
    val quiz: DogamQuiz? = null, val seasonVariant: DogamSeasonVariant? = null,
    val active1st: Boolean = false, val backup: Boolean = false, val reserved: Boolean = false,
    val useIf: String? = null, val feature: String = "",
)

/** The supplied catalog is data, not a source of executable API names or network requests. */
class DogamCatalog private constructor(
    val title: String, val types: Map<String, String>, val rules: Map<String, String>,
    val cards: List<DogamCard>, val raw: JsonObject,
) {
    val byNo: Map<Int, DogamCard> = cards.associateBy { it.no }
    val byId: Map<String, DogamCard> = cards.filter { it.id != null }.associateBy { it.id!! }
    val carCards: List<DogamCard> = cards.filter { it.kind == "car" && !it.reserved }
    val quizCards: List<DogamCard> = cards.filter { it.kind == "quiz" && !it.reserved }

    companion object {
        fun fromResources(): DogamCatalog = fromJson(requireNotNull(
            DogamCatalog::class.java.getResourceAsStream("/content/cards_v1_1.json")) {
            "Missing catalog resource: cards_v1_1.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() })

        fun fromJson(text: String): DogamCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val featureCatalog = root["schemaVersion"] == JsonPrimitive("1+feature")
            require(root["schemaVersion"] == JsonPrimitive(1) || featureCatalog)
            if (featureCatalog) require(root.getValue("rules").jsonObject.string("emptySlot").isNotBlank())
            val types = root.getValue("types").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
            val rules = root.getValue("rules").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
            val cards = root.array("cards").map { element ->
                val c = element.jsonObject
                fun text(key: String): String = c[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                fun flag(key: String): Boolean = c[key]?.jsonPrimitive?.booleanOrNull == true
                if (featureCatalog && !flag("reserved")) {
                    require((c["feature"] as? JsonPrimitive)?.isString == true)
                }
                val back = (c["back"] as? JsonObject)?.let { b ->
                    val s = b.getValue("source").jsonObject
                    val source = DogamSource(s.string("url"), s.string("section"), s.string("model"))
                    val uri = java.net.URI(source.url)
                    require(uri.scheme == "https" && uri.host == "ownersmanual.hyundai.com" &&
                        uri.userInfo == null && source.section.isNotBlank() && source.model.isNotBlank())
                    DogamBack(b.string("where"), b.string("how"), b.string("goodDay"), b.string("note"), source)
                }
                val quiz = (c["quiz"] as? JsonObject)?.let { q ->
                    DogamQuiz(q.string("q"), q.array("choices").map { it.jsonPrimitive.content }, q.int("answer"))
                        .also { require(it.q.isNotBlank() && it.choices.size == 3 &&
                            it.choices.all(String::isNotBlank) && it.answer in it.choices.indices) }
                }
                val season = (c["seasonVariant"] as? JsonObject)?.let { s ->
                    DogamSeasonVariant(s.string("name"), s.int("rarity"),
                        s.array("months").map { it.jsonPrimitive.int }, s.string("rule"))
                        .also { require(it.name.isNotBlank() && it.rarity > 0 && it.months.isNotEmpty() &&
                            it.months.distinct().size == it.months.size && it.months.all { month -> month in 1..12 }) }
                }
                DogamCard(c.int("no"), c.string("kind"), c["id"]?.jsonPrimitive?.contentOrNull,
                    text("name"), text("type"), c["rarity"]?.jsonPrimitive?.intOrNull ?: 0,
                    (c["sdk"] as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.content }.orEmpty(),
                    text("discover"), text("riddle"), text("hint2"), back, quiz, season,
                    flag("active1st"), flag("backup"), flag("reserved"), c["useIf"]?.jsonPrimitive?.contentOrNull,
                    feature = text("feature"))
            }
            require(cards.size == 100 && cards.map { it.no }.toSet() == (1..100).toSet())
            require(cards.count { it.active1st } == 12 && cards.count { it.backup } == 1 && cards.count { it.reserved } == 87)
            require(cards.all { it.kind in setOf("car", "quiz") &&
                listOf(it.active1st, it.backup, it.reserved).count { value -> value } == 1 })
            val named = cards.filterNot { it.reserved }
            if (featureCatalog) require(named.all { it.feature.isNotBlank() })
            require(named.map { it.id }.distinct().size == named.size && named.all {
                it.id?.matches(Regex("[a-z][a-z0-9_]*")) == true && it.name.isNotBlank() &&
                    it.type in types && it.rarity > 0 && it.riddle.isNotBlank() && it.back != null &&
                    ((it.kind == "quiz") == (it.quiz != null))
            })
            require(cards.filter { it.active1st && it.kind == "car" }.map { it.no } == (1..8).toList())
            require(cards.filter { it.active1st && it.kind == "quiz" }.map { it.no } == (41..44).toList())
            require(cards.single { it.backup }.no == 9)
            return DogamCatalog(root.string("title"), types, rules, cards, root)
        }
    }
}
