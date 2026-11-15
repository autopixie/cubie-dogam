package ai.upet.core

import java.text.Normalizer
import kotlinx.serialization.json.*

data class CardSource(val url: String, val section: String, val kind: String = "unverified", val verified: String? = null, val model: String? = null) {
    val approvedManualSource: Boolean get() = kind == "owners_manual" &&
        url == "https://ownersmanual.hyundai.com/full_webhelp/CN8/2027/ko_KR/convenience-use_steering_wheel-use_steering_wheel_heater.html" && section == "핸들 온열 사용하기" &&
        model == "디 올 뉴 아반떼 2027 취급설명서" && verified != null &&
        runCatching { java.time.LocalDate.parse(verified).toString() == verified }.getOrDefault(false)
    val approvedInstructionSource: Boolean get() = approvedManualSource || approvedScreenSource
    val approvedScreenSource: Boolean get() = kind == "pleos_screen" && section.isNotBlank() &&
        url == "https://www.hyundaimotorgroup.com/ko/story/pleos-connect-next-generation-infotainment-system" &&
        verified != null && runCatching { java.time.LocalDate.parse(verified).toString() == verified }.getOrDefault(false)
    val vehicleControlSource: Boolean get() = kind=="vehicle_controls" && section.isNotBlank() &&
        runCatching { val uri=java.net.URI(url);uri.scheme=="https" && uri.host in setOf("www.hyundaimotorgroup.com","hyundaimotorgroup.com","ownersmanual.hyundai.com") }.getOrDefault(false)
}
data class FeatureCard(val id: String, val model: String?, val name: String, val aliases: List<String>,
    val location: String, val operation: String, val goodWhen: String, val caution: String,
    val practiceId: String?, val locationSource: CardSource?, val operationSource: CardSource?,
    val referenceOnly: Boolean = false, val manualAnswer: String = "")
class FeatureCards(val cards: List<FeatureCard>) {
    init {
        require(cards.isNotEmpty() && cards.map { it.id }.distinct().size == cards.size)
        require(cards.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) && it.name.isNotBlank() && it.aliases.all(String::isNotBlank) })
    }
    companion object {
        fun fromResources() = fromJson(requireNotNull(FeatureCards::class.java.getResourceAsStream("/content/feature_cards_v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        fun fromJson(text: String): FeatureCards {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.int("schemaVersion") == 1)
            fun source(o: JsonObject, key: String): CardSource? {
                val raw = o[key] as? JsonObject ?: return null
                val url = raw["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val section = raw["section"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val proof=CardSource(url,section,raw["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    raw["verified"]?.jsonPrimitive?.contentOrNull, raw["model"]?.jsonPrimitive?.contentOrNull)
                return proof.takeIf { it.vehicleControlSource || it.approvedInstructionSource }
            }
            return FeatureCards(root.array("cards").map {
                val c = it.jsonObject
                FeatureCard(c.string("id"), c["model"]?.jsonPrimitive?.contentOrNull,
                    c.string("name"), c.array("aliases").map { a -> a.jsonPrimitive.content },
                    c.string("location"), c.string("operation"), c.string("goodWhen"), c.string("caution"),
                    c["practiceId"]?.jsonPrimitive?.contentOrNull, source(c, "locationSource"), source(c, "operationSource"),
                    c["referenceOnly"]?.jsonPrimitive?.booleanOrNull == true, c["manualAnswer"]?.jsonPrimitive?.contentOrNull.orEmpty())
            })
        }
    }
}
sealed interface QuestionResult {
    data object Empty: QuestionResult
    data object Unknown: QuestionResult
    data object Safety: QuestionResult
    data class Clarify(val candidates: List<FeatureCard>, val prompt: String): QuestionResult
    data class Answer(val card: FeatureCard, val text: String): QuestionResult
}
/** A bounded local name lookup. No network, storage, vehicle events or question logging. */
class LocalQuestionAnswer(private val catalog: FeatureCards) {
    companion object {
        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    }
    private val suffix = Regex("(?:은|는|이|가|을|를|에|에서|로|으로|도|만)?(?:어디(?:에|서)?(?:있어|있어요|있나요|켜|켜요|켜나요|조절해|조절해요)?|어떻게(?:켜|켜요|켜나요|쓰는거야|사용해|사용해요)?|알려줘|알려주세요|뭐야|뭔가요|설명해줘|켜줘|켜주세요|켜|켜요|사용법)?")
    private fun named(q: String, alias: String): Boolean {
        val a = normalize(alias)
        val start = q.indexOf(a)
        if (start < 0) return false
        val prefix = q.substring(0, start)
        return prefix in setOf("", "큐비", "큐비야", "차의", "이차의", "어디서", "어떻게") && suffix.matches(q.substring(start + a.length))
    }
    fun ask(raw: String, currentModel: String? = null): QuestionResult {
        val q = normalize(raw.take(500))
        if (q.isEmpty()) return QuestionResult.Empty
        if (listOf("고장", "경고", "수리", "이상", "안켜", "작동안", "불량").any { it in q }) return QuestionResult.Safety
        val exact = catalog.cards.filter { c -> (listOf(c.name) + c.aliases).any { named(q, it) } }
        val parts = q.split(Regex("하고|그리고|말고|아니고|제외|와|과|랑|및"))
        val mentioned = catalog.cards.filter { c -> parts.any { part -> (listOf(c.name) + c.aliases).any { named(part, it) } } }
        if (mentioned.size > 1 || (mentioned.isNotEmpty() && listOf("말고", "아니고", "제외").any { it in q }))
            return QuestionResult.Clarify(mentioned, "어느 기능을 알아볼까요? 하나를 골라 주세요.")
        if (exact.size == 1) return answer(exact.single(), currentModel)
        if (exact.size > 1) return QuestionResult.Clarify(exact, "어느 기능을 알아볼까요?")
        if (named(q, "열선")) {
            val options = catalog.cards.filter { "열선" in it.name }
            return QuestionResult.Clarify(options.takeIf { it.size > 1 }.orEmpty(), if(options.size>1) "어떤 열선을 알아볼까요?" else "핸들 열선인지 좌석 열선인지 기능 이름을 더 적어 주세요. 자료가 없는 기능은 아직 답할 수 없어요.")
        }
        if (named(q, "에어컨") || named(q, "공조")) {
            val options = catalog.cards.filter { "공조" in it.name }
            return QuestionResult.Clarify(options.takeIf { it.size > 1 }.orEmpty(), if(options.size>1) "어떤 공조 기능을 알아볼까요?" else "자동 공조인지 공조 화면인지 기능 이름을 더 적어 주세요.")
        }
        return QuestionResult.Unknown
    }
    fun answer(card: FeatureCard, currentModel: String? = null): QuestionResult.Answer {
        if (card.id == "steering_heat" && card.model == null && !card.referenceOnly &&
            card.practiceId == "steering_heat" && card.location.isNotBlank() &&
            card.locationSource?.approvedManualSource == true) {
            val operationProven = card.operation.isNotBlank() && card.operationSource?.approvedManualSource == true
            val text = if (operationProven && card.manualAnswer.isNotBlank()) card.manualAnswer
                else "취급설명서 기준으로 ${card.location}." +
                    if (operationProven) " ${card.operation}" else ""
            return QuestionResult.Answer(card, text)
        }
        if (card.model == null && !card.referenceOnly && !card.practiceId.isNullOrBlank() &&
            card.location.isNotBlank() && card.locationSource?.approvedScreenSource == true) {
            val facts = mutableListOf("Pleos Connect 화면 기준으로 ${card.location}에서 켤 수 있어요.")
            if (card.operation.isNotBlank() && card.operationSource?.approvedScreenSource == true) facts += card.operation
            facts += "실제 차의 버튼 위치는 사용설명서도 함께 확인해 주세요."
            facts += "버튼은 사용자가 직접 눌러요."
            return QuestionResult.Answer(card, facts.joinToString(" "))
        }
        val applies = !card.model.isNullOrBlank() && (card.model == currentModel || (currentModel == null && card.referenceOnly))
        val facts = mutableListOf<String>()
        if (applies && card.locationSource?.vehicleControlSource == true && card.location.isNotBlank()) facts +=
            if(card.referenceOnly) "${card.model} 기준으로 ${card.location}에서 조작할 수 있어요." else card.location + "."
        if (applies && card.operationSource?.vehicleControlSource == true && card.operation.isNotBlank()) facts += card.operation + "."
        if (facts.isEmpty() || !applies || card.locationSource?.vehicleControlSource != true || card.operationSource?.vehicleControlSource != true)
            facts += if(card.referenceOnly) "이 차의 사양과 정확한 사용법은 사용설명서의 ‘${card.name}’ 항목을 확인해 주세요."
                else "사용설명서의 ‘${card.name}’ 항목을 확인해 주세요."
        facts += "버튼은 사용자가 직접 눌러요."
        return QuestionResult.Answer(card, facts.joinToString(" "))
    }
}
/** RAM-only tokens: lifecycle/typing/close invalidate every pending response. */
class ConversationState {
    private var generation = 0L
    var open = false; private set
    var answer: QuestionResult? = null; private set
    fun begin(): Long { generation++; open = true; answer = null; return generation }
    fun typing(): Long { generation++; answer = null; return generation }
    fun token(): Long = generation
    fun complete(token: Long, safe: Boolean, value: QuestionResult): Boolean {
        if (!open || !safe || token != generation) return false
        answer = value; return true
    }
    fun close() { generation++; open = false; answer = null }
}
