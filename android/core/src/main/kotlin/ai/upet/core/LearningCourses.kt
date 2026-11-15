package ai.upet.core

import kotlinx.serialization.json.*

data class LearningCourse(val id: String, val title: String, val displayOrder: Int, val unitIds: List<String>,
    val releaseScope: String, val practiceEnabled: Boolean, val unlockRuleId: String, val lockedCopy: String)
data class CourseRule(val id: String, val kind: String, val persistencePolicy: String)
data class CourseObservation(val observationId: String, val signalKind: String, val rawValue: String?,
    val interpretedOn: Boolean?, val validity: Boolean, val observedAt: String, val evaluationDate: String,
    val parkedConfirmed: Boolean, val origin: String, val vehicleScope: String, val epoch: Long,
    val capabilityConfirmed: Boolean, val measurementEvidenceVersion: String?, val evaluationId: String)
data class AcceptedCourseObservation(val observation: CourseObservation, val acceptedAt: String,
    val acceptedUnderEpoch: Long, val acceptedEvidenceVersion: String)
data class CourseContext(val date: String, val origin: String, val vehicleScope: String, val epoch: Long,
    val evaluationId: String, val parked: Boolean, val measurementEvidenceVersion: String?, val storedReuseConfirmed: Boolean = false)
data class CourseDecision(val courseId: String, val ruleVersion: Int, val evidenceIds: List<String>,
    val contextConditionMet: Boolean, val practiceAvailable: Boolean)
class LearningCourses(val courses: List<LearningCourse>, val rules: Map<String,CourseRule>, val ruleVersion: Int) {
    val firstWeek: LearningCourse get() = courses.single { it.id=="first_week" }
    val firstSubmissionPracticeIds: Set<String> get() = courses.filter { it.releaseScope=="first_submission" && it.practiceEnabled && it.id=="first_week" && rules[it.unlockRuleId]?.kind=="always" }
        .flatMap { it.unitIds }.toSet()
    fun decision(courseId: String, accepted: List<AcceptedCourseObservation>, context: CourseContext): CourseDecision {
        val course=courses.firstOrNull { it.id==courseId }
            ?: return CourseDecision(courseId,ruleVersion,emptyList(),false,false)
        val rule=rules[course.unlockRuleId]
        val evidence=if(rule?.kind=="parked_wiper_on_today") accepted.filter { a ->
            val o=a.observation
            val live=o.evaluationId==context.evaluationId && a.acceptedUnderEpoch==context.epoch
            val reusable=context.storedReuseConfirmed && rule.persistencePolicy=="confirmed_same_day_reuse"
            (live || reusable) && o.signalKind=="wiper" && o.validity && o.capabilityConfirmed && o.interpretedOn==true &&
                o.parkedConfirmed && o.origin==context.origin && o.vehicleScope==context.vehicleScope &&
                o.evaluationDate==context.date && o.measurementEvidenceVersion!=null &&
                o.measurementEvidenceVersion==context.measurementEvidenceVersion && a.acceptedEvidenceVersion==o.measurementEvidenceVersion
        } else emptyList()
        val met=rule?.kind=="always" || (context.parked && rule?.kind=="parked_wiper_on_today" && evidence.isNotEmpty())
        val available=met && course.id=="first_week" && course.releaseScope=="first_submission" && course.practiceEnabled && course.unitIds.isNotEmpty()
        return CourseDecision(course.id,ruleVersion,evidence.map { it.observation.observationId },met,available)
    }
    companion object {
        fun fromResources(): LearningCourses = fromJson(requireNotNull(LearningCourses::class.java.getResourceAsStream("/content/learning_courses_v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() },ContentBundle.fromResources().features.map { it.id }.toSet())
        fun fromJson(text: String, featureIds: Set<String>): LearningCourses {
            val j=Json.parseToJsonElement(text).jsonObject;require(j.int("schemaVersion")==1 && j.int("ruleVersion")==1)
            val courses=j.array("courses").map { val c=it.jsonObject
                LearningCourse(c.string("id"),c.string("title"),c.int("displayOrder"),c.array("unitIds").map { u->u.jsonPrimitive.content },
                    c.string("releaseScope"),c.bool("practiceEnabled"),c.string("unlockRuleId"),c.string("lockedCopy")) }
            require(courses.map { it.id }.distinct().size==courses.size)
            require(courses.map { it.displayOrder }.distinct().size==courses.size)
            require(courses.all { it.unitIds.distinct().size==it.unitIds.size && it.unitIds.all { id->id in featureIds } })
            require(courses.single { it.id=="first_week" }.unitIds.isNotEmpty())
            val rawRules=j.array("rules").map { it.jsonObject };require(rawRules.map { it.string("id") }.distinct().size==rawRules.size)
            val rules=rawRules.associate { r->r.string("id") to CourseRule(r.string("id"),r.string("conditionKind"),r.string("persistencePolicy")) }
            return LearningCourses(courses,rules,j.int("ruleVersion"))
        }
        fun acceptLive(o: CourseObservation, context: CourseContext, acceptedAt: String): AcceptedCourseObservation? {
            val observedDate=runCatching { java.time.Instant.parse(o.observedAt).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString() }.getOrNull()
            if(observedDate!=o.evaluationDate || runCatching { java.time.Instant.parse(acceptedAt) }.isFailure ||
                context.vehicleScope.isBlank() || context.evaluationId.isBlank() || o.origin !in setOf("demo","vehicle") || !context.parked || !o.parkedConfirmed || o.epoch!=context.epoch || o.evaluationId!=context.evaluationId ||
                o.origin!=context.origin || o.vehicleScope!=context.vehicleScope || !o.validity || !o.capabilityConfirmed ||
                o.measurementEvidenceVersion.isNullOrBlank() || o.measurementEvidenceVersion!=context.measurementEvidenceVersion ||
                o.evaluationDate!=context.date || o.observationId.isBlank() || o.interpretedOn==null) return null
            return AcceptedCourseObservation(o,acceptedAt,context.epoch,o.measurementEvidenceVersion)
        }
    }
}
