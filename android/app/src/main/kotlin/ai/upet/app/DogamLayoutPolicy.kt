package ai.upet.app

/** Geometry from the supplied 1680 x 1200 mock, with scrolling rather than truncated copy. */
internal object DogamLayoutPolicy {
    data class Binder(val canvasWidth: Int, val sideBySide: Boolean, val sideWidth: Int,
        val padding: Int, val columns: Int, val cellWidth: Int, val cellHeight: Int, val gap: Int)

    fun binder(width: Int, height: Int): Binder {
        val canvas = width.coerceAtLeast(320)
        val sideBySide = canvas >= 1300 && canvas >= height
        val side = if (sideBySide) (canvas * 500 / 1680).coerceIn(360, 500) else 0
        val padding = if (canvas >= 1000) 48 else 20
        val available = (canvas - side - padding * 2).coerceAtLeast(1)
        val gap = 20
        val columns = ((available + gap) / 218).coerceIn(1, 5)
        val cell = minOf(198, (available - gap * (columns - 1)) / columns).coerceAtLeast(1)
        return Binder(canvas, sideBySide, side, padding, columns, cell, 250, gap)
    }

    fun horizontalHeader(width: Int, fontScale: Float = 1f): Boolean {
        val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return width >= 900f * scale
    }

    fun rows(slotCount: Int, columns: Int): Int {
        require(slotCount >= 0 && columns > 0)
        return (slotCount + columns - 1) / columns
    }

    fun dateLine(date: String?): String = date?.let {
        runCatching { java.time.LocalDate.parse(it).let { d -> "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일에 만남" } }.getOrNull()
    }.orEmpty()

}
