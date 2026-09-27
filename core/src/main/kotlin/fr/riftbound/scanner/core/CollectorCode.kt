package fr.riftbound.scanner.core

private val PREMIER_NOMBRE = Regex("[0-9]+")

/** Forme de reference d'un code : minuscule, sans zeros de tete sur le numero. */
fun canonicalCode(code: String?): String? {
    if (code == null) return null
    val parts = code.lowercase().split("-").toMutableList()
    if (parts.size < 2) return code.lowercase()
    val trouve = PREMIER_NOMBRE.find(parts[1])
    if (trouve != null) {
        val sansZeros = trouve.value.trimStart('0').ifEmpty { "0" }
        parts[1] = parts[1].replaceRange(trouve.range, sansZeros)
    }
    return parts.joinToString("-")
}
