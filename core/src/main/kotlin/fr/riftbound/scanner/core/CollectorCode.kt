package fr.riftbound.scanner.core

private val FIRST_NUMBER = Regex("[0-9]+")

/** Forme de reference d'un code : minuscule, sans zeros de tete sur le numero. */
fun canonicalCode(code: String?): String? {
    if (code == null) return null
    val parts = code.lowercase().split("-").toMutableList()
    if (parts.size < 2) return code.lowercase()
    val match = FIRST_NUMBER.find(parts[1])
    if (match != null) {
        val withoutLeadingZeros = match.value.trimStart('0').ifEmpty { "0" }
        parts[1] = parts[1].replaceRange(match.range, withoutLeadingZeros)
    }
    return parts.joinToString("-")
}
