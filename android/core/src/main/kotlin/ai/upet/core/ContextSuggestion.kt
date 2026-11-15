package ai.upet.core

import java.time.LocalDate
import kotlinx.serialization.json.*

data class K1Policy(val coldThreshold: Double?, val unit: String?, val meaningVersion: String?, val freshnessContract: String?)
data class K1Observation(val temperature: Double?, val unit: String?, val meaningVersion: String?,
    val sameEvaluation: Boolean, val freshHeaterOff: Boolean, val validArea: Boolean)
data class SuggestionCandidate(val id: String, val capabilityConfirmed: Boolean, val completed: Boolean)
data class ContextProposal(val featureId: String, val text: String, val temperatureSpecific: Boolean)
object ContextSuggestion {
    fun choose(candidates: List<SuggestionCandidate>, safe: Boolean, practiceActive: Boolean,
        policy: K1Policy, observation: K1Observation?): ContextProposal? {
        if (!safe || practiceActive) return null
        val eligible = candidates.filter { it.capabilityConfirmed && !it.completed }
        val t = observation?.temperature
        val threshold = policy.coldThreshold
        val verified = policy.unit != null && policy.meaningVersion != null && policy.freshnessContract != null &&
            observation?.meaningVersion == policy.meaningVersion && observation?.unit == policy.unit &&
            observation?.sameEvaluation == true && observation.validArea && observation.freshHeaterOff
        if (eligible.any { it.id == "steering_heat" } && verified && t != null && t.isFinite() &&
            threshold != null && threshold.isFinite() && t < threshold)
            return ContextProposal("steering_heat", "실내가 서늘하고 핸들 열선이 꺼져 있어요. 사용법을 살펴볼까요?", true)
        // An unknown/high temperature or non-Off heater must never route back to a heat CTA.
        val other = eligible.firstOrNull { it.id != "steering_heat" } ?: return null
        return ContextProposal(other.id, "주차 중 필요한 기능을 살펴볼까요?", false)
    }
}
data class SuggestionOffer(val id: String, val scope: String, val featureId: String, val day: String)
enum class SuggestionReply { ALREADY_KNOW, NOT_NEEDED, LATER, OPEN }
data class SuggestionLedger(val lastDay: String? = null, val pending: List<SuggestionOffer> = emptyList(),
    val permanent: Set<String> = emptySet(), val later: Map<String,String> = emptyMap()) {
    private fun key(scope: String, id: String) = scope + ":" + id
    fun excluded(scope: String, id: String, day: String): Boolean = key(scope,id) in permanent ||
        pending.any { it.scope == scope && it.featureId == id } || (later[key(scope,id)]?.let { day <= it } == true)
    fun reserve(scope: String, featureId: String, day: String): Pair<SuggestionLedger,SuggestionOffer?> {
        LocalDate.parse(day)
        require(scope.isNotBlank() && featureId.isNotBlank())
        if ((lastDay != null && day <= lastDay) || excluded(scope,featureId,day)) return this to null
        val offer = SuggestionOffer(day + ":" + scope + ":" + featureId,scope,featureId,day)
        return copy(lastDay=day,pending=pending+offer) to offer
    }
    fun reply(id: String, response: SuggestionReply): SuggestionLedger {
        val offer = pending.singleOrNull { it.id == id } ?: return this
        val key=key(offer.scope,offer.featureId)
        return copy(pending=pending.filterNot { it.id==id },
            permanent=if(response in setOf(SuggestionReply.ALREADY_KNOW,SuggestionReply.NOT_NEEDED)) permanent+key else permanent,
            later=if(response==SuggestionReply.LATER) later+(key to offer.day) else later)
    }
    fun toJson(): String = buildJsonObject {
        put("version",1);put("lastDay",lastDay?.let(::JsonPrimitive) ?: JsonNull)
        put("pending",buildJsonArray { pending.forEach { o -> add(buildJsonObject {
            put("id",o.id);put("scope",o.scope);put("featureId",o.featureId);put("day",o.day)
        }) } })
        put("permanent",buildJsonArray { permanent.sorted().forEach { add(it) } })
        put("later",buildJsonObject { later.toSortedMap().forEach { (k,v)->put(k,v) } })
    }.toString()
    companion object {
        fun fromJson(text: String): SuggestionLedger {
            val j=Json.parseToJsonElement(text).jsonObject;require(j.int("version")==1)
            require(j.keys.containsAll(setOf("version","lastDay","pending","permanent","later")))
            val day=j["lastDay"]?.jsonPrimitive?.contentOrNull;day?.let { LocalDate.parse(it) }
            val pending=j.array("pending").map { val o=it.jsonObject
                LocalDate.parse(o.string("day")); SuggestionOffer(o.string("id"),o.string("scope"),o.string("featureId"),o.string("day")) }
            require(pending.map { it.id }.distinct().size==pending.size)
            val permanent=j.array("permanent").map { it.jsonPrimitive.content }.toSet()
            val later=j.getValue("later").jsonObject.mapValues { LocalDate.parse(it.value.jsonPrimitive.content).toString() }
            require(pending.all { it.scope.isNotBlank() && it.featureId.matches(Regex("[a-z][a-z0-9_]*")) && it.id==it.day+":"+it.scope+":"+it.featureId })
            require(pending.map { it.scope+":"+it.featureId }.distinct().size==pending.size)
            require(day!=null || (pending.isEmpty() && permanent.isEmpty() && later.isEmpty()))
            require(pending.all { day!=null && it.day<=day } && later.values.all { day!=null && it<=day })
            return SuggestionLedger(day,pending,permanent,later)
        }
    }
}
