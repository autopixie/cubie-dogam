package ai.upet.app

import ai.upet.core.ContentBundle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Display copy comes from the core's bundled content, not another rule table. */
internal class ContentCopy(content: ContentBundle) {
    data class SignalLabels(val card: String, val inactive: String, val active: String)
    data class Place(val id: String, val name: String, val description: String)

    val signals: Map<String, SignalLabels> = content.sources.getValue("features")
        .getValue("features").jsonArray.associate { element ->
            val feature = element.jsonObject
            val signal = feature.getValue("signal").jsonObject
            feature.text("id") to SignalLabels(signal.text("consoleCard"),
                signal.text("inactiveLabel"), signal.text("activeLabel"))
        }
    val places: List<Place> = content.sources.getValue("places")
        .getValue("templates").jsonArray.map { element ->
            val place = element.jsonObject
            Place(place.text("id"), place.text("name"), place.text("description"))
        }
    private val memory = content.sources.getValue("memories")
        .getValue("templates").jsonArray.first().jsonObject
    val memoryTitle = memory.text("title")
    val memoryLine = memory.getValue("lines").jsonArray.first().jsonPrimitive.content
    val distanceLabel = memory.text("distanceLabel")
    val unknownDistance = memory.text("unknownDistance")
    val invalidDistance = memory.text("invalidDistance")
    val distanceExplanation = memory.text("distanceExplanation")
    val unknownExplanation = memory.text("unknownExplanation")
    val invalidExplanation = memory.text("invalidExplanation")

    fun homeTitle(featureName: String?): String = if (featureName == null) "함께 쉬어 갈까요?" else featureName + " 살펴보기"
    val homeDetail = "주차 중일 때만 함께 연습해요."
    val warningSpeech = listOf("타이어가 조금 걱정돼요.", "계기판 안내를 먼저 확인해 주세요.")
    val questionHelp = "기능 이름을 적어 주세요. 질문은 저장하지 않아요."
    val questionPracticePrompt = "지금 해 볼래요?"
    fun result(kind: String?, featureName: String): List<String> {
        val title = when (kind) { "success" -> "해냈어요!"; "repeat" -> "다시 해냈어요!"; else -> "이미 켜져 있어요" }
        val line = if (kind == "success" && featureName.contains("핸들")) "핸들이 따뜻해지기 시작했어요."
            else if (kind == "already_on") "지금 상태를 함께 확인했어요." else "차에서 직접 켠 것을 확인했어요."
        return listOf(title, line)
    }
    fun questionMessage(result: ai.upet.core.QuestionResult): String = when (result) {
        is ai.upet.core.QuestionResult.Answer -> result.text
        is ai.upet.core.QuestionResult.Clarify -> result.prompt
        ai.upet.core.QuestionResult.Safety -> "고장 원인을 진단할 수는 없어요. 안전하게 주차한 뒤 계기판 안내와 이 차의 사용설명서를 확인하고, 필요하면 점검받아 주세요."
        ai.upet.core.QuestionResult.Unknown -> "아직 모르는 기능이에요. 이 차의 사용설명서를 확인해 주세요."
        ai.upet.core.QuestionResult.Empty -> "기능 이름을 적어 주세요."
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
