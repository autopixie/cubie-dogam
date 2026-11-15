package ai.upet.app

import ai.upet.core.Gear
import ai.upet.core.Tpms

internal enum class PetFace { SMILE, JOY, WORRIED, REST, WAITING, TALKING }

/** Rendering policy only: never dispatches events or awards progress. */
internal object PetPresentation {
    @JvmOverloads
    fun face(allowed: Boolean, gear: Gear, tpms: Tpms?, outcome: String?, waiting: Boolean = false, talking: Boolean = false, fuelDemoEnabled: Boolean = false): PetFace = when {
        !allowed || gear != Gear.P -> PetFace.REST
        tpms == Tpms.On || tpms == Tpms.Blink || (tpms == null && ProductPolicy.tpmsUnknownWorried) -> PetFace.WORRIED
        outcome in setOf("success", "parking", "place") || (fuelDemoEnabled && outcome == "fuel_increase") -> PetFace.JOY
        talking -> PetFace.TALKING
        waiting -> PetFace.WAITING
        else -> PetFace.SMILE
    }

    fun horizontal(width: Int, height: Int): Boolean = width >= height
    const val MAX_SPEECH_WIDTH_DP = 540
    fun speechWidth(availableDp: Int): Int = minOf(MAX_SPEECH_WIDTH_DP, availableDp.coerceAtLeast(0))
    fun heroWidth(availableDp: Int): Int = minOf(620, ((availableDp - 32) * .48f).toInt().coerceAtLeast(0))
}

internal enum class SpeechSituation { OFFER, PRACTICE, SUCCESS, REPEAT, ALREADY_ON, ANSWER, WARNING, REST, HOME }
internal enum class PetReactionKind { NONE, NOD, BOUNCE }
internal data class PetSpeech(val face: PetFace, val lines: List<String>, val source: String, val visible: Boolean)

/** Copy is supplied by existing content/copy; rendering cannot change learning progress. */
internal object PetSpeechPolicy {
    fun speech(situation: SpeechSituation, allowed: Boolean, gear: Gear, tpms: Tpms?,
        lines: List<String>, source: String): PetSpeech {
        require(source.isNotBlank())
        if (!allowed || gear != Gear.P || situation == SpeechSituation.REST)
            return PetSpeech(PetFace.REST, emptyList(), source, false)
        val face = when {
            tpms == Tpms.On || tpms == Tpms.Blink || situation == SpeechSituation.WARNING -> PetFace.WORRIED
            situation == SpeechSituation.SUCCESS -> PetFace.JOY
            situation == SpeechSituation.REPEAT || situation == SpeechSituation.ALREADY_ON -> PetFace.SMILE
            situation == SpeechSituation.OFFER || situation == SpeechSituation.ANSWER -> PetFace.TALKING
            situation == SpeechSituation.PRACTICE -> PetFace.WAITING
            else -> PetFace.SMILE
        }
        return PetSpeech(face, lines.filter { it.isNotBlank() }, source, lines.any { it.isNotBlank() })
    }
    fun reaction(speech: PetSpeech, fresh: Boolean, foreground: Boolean, allowed: Boolean,
        reducedMotion: Boolean): PetReactionKind = when {
        !fresh || !speech.visible || !foreground || !allowed || reducedMotion -> PetReactionKind.NONE
        speech.face == PetFace.JOY -> PetReactionKind.BOUNCE
        else -> PetReactionKind.NOD
    }
}

/** Semantic occurrence keys survive full SDK-triggered view rebuilds. No question text is logged. */
internal class PetReactionTicket(val kind: PetReactionKind) {
    private var startedAt: Long? = null
    private var cancelled = false
    private var resumeAt: Long? = null
    val durationMs: Long get() = if (kind == PetReactionKind.BOUNCE) 800L else 300L
    fun playhead(now: Long): Long? {
        if (cancelled || kind == PetReactionKind.NONE) return null
        resumeAt?.let { saved -> resumeAt = null; startedAt = now - saved; return saved }
        if (startedAt == null) startedAt = now
        return (now - requireNotNull(startedAt)).coerceIn(0, durationMs - 1)
    }
    fun blocksTravel(): Boolean = !cancelled && kind != PetReactionKind.NONE
    fun pauseAt(playheadMs: Long) {
        if (!cancelled) resumeAt = playheadMs.coerceIn(0, durationMs - 1)
    }
    fun finish() { cancelled = true }
    fun cancel() { cancelled = true }
}

internal class PetSpeechOccurrences(private var previous: String? = null) {
    private var ticket = PetReactionTicket(PetReactionKind.NONE)
    private var shownAt: Long? = null
    fun markShown(key: String, now: Long): Boolean {
        if (key != previous || shownAt != null) return false
        shownAt = now; return true
    }
    fun readingUntilMs(): Long = if (previous == null) 0L else shownAt?.let { it + ai.upet.core.PetMotionPolicy.READ_MS } ?: Long.MAX_VALUE
    fun ticket(): PetReactionTicket = ticket
    fun current(): String? = previous
    @JvmOverloads
    fun show(key: String?, kind: PetReactionKind = PetReactionKind.NOD): Boolean {
        if (key == previous) return false
        ticket.cancel()
        shownAt = null
        ticket = PetReactionTicket(if (key == null) PetReactionKind.NONE else kind)
        previous = key
        return key != null
    }
}
