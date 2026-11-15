package ai.upet.core

object DogamKorean {
    fun objectParticle(name: String): String {
        val last = name.lastOrNull { !it.isWhitespace() }?.code ?: return "를"
        return if (last in 0xAC00..0xD7A3 && (last - 0xAC00) % 28 != 0) "을" else "를"
    }
    fun encounter(name: String): String = name + objectParticle(name) + " 만났어요"
    fun number(no: Int): String = "No." + no.toString().padStart(3, '0')
}
