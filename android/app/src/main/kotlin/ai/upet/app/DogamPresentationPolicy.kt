package ai.upet.app

/** Display and navigation rules only. This cannot grant discoveries or access vehicle/storage state. */
internal object DogamPresentationPolicy {
    data class Card(
        val no: Int, val feature: String = "", val friendName: String = "", val kind: String = "car",
        val found: Boolean = false, val reserved: Boolean = false,
        val where: String = "", val how: String = "", val sourceSection: String = "",
    )
    enum class Destination { BINDER, GUIDE, QUIZ, DETAIL }
    data class Cell(val label: String, val accessibilityLabel: String, val silhouette: Boolean)
    data class Field(val label: String, val value: String)
    // Discovery-only copy and quiz answers have no field in this instruction view.
    data class Guide(val feature: String, val fields: List<Field>, val sourceSection: String)

    fun destination(card: Card): Destination = when {
        card.reserved -> Destination.BINDER
        card.found -> Destination.DETAIL
        card.kind == "quiz" -> Destination.QUIZ
        else -> Destination.GUIDE
    }

    fun cell(card: Card): Cell {
        val no = "No." + card.no.toString().padStart(3, '0')
        if (card.reserved) return Cell("?", "$no 곧 만날 친구", true)
        val label = if (card.found) card.friendName else card.feature
        return Cell(label, "$no $label" + if (card.found) "" else " 미발견", !card.found)
    }

    fun guide(card: Card): Guide? = if (destination(card) == Destination.GUIDE)
        Guide(card.feature, listOf(Field("어디 있나", card.where), Field("켜는 법", card.how)), card.sourceSection)
        else null

    /** Catalog-wide identities include friends omitted from the currently supported slots. */
    fun hint(original: String, cards: List<Card>): String? {
        val hidden = cards.filter { !it.found && it.friendName.isNotBlank() }.associateBy { it.friendName }
        if (hidden.isEmpty()) return original
        val names = hidden.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
        val matches = Regex("($names)(과|와)?").findAll(original).toList()
        if (matches.isEmpty()) return original
        val shown = StringBuilder()
        var cursor = 0
        for (match in matches) {
            val feature = hidden.getValue(match.groupValues[1]).feature
            if (feature.isBlank()) return null
            shown.append(original, cursor, match.range.first).append(feature)
            if (match.groupValues[2].isNotEmpty()) {
                val last = feature.trimEnd().lastOrNull() ?: return null
                if (last !in '가'..'힣') return null
                shown.append(if ((last.code - '가'.code) % 28 == 0) "와" else "과")
            }
            cursor = match.range.last + 1
        }
        shown.append(original, cursor, original.length)
        val result = shown.toString()
        return result.takeUnless { value -> hidden.keys.any { it in value } }
    }

    /** The same one daily riddle remains independent of tabs and selected cards. */
    fun today(cards: List<Card>): Int? = cards.filter { !it.reserved && !it.found }.minOfOrNull { it.no }
}
