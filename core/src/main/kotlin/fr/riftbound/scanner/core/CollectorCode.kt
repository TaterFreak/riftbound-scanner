package fr.riftbound.scanner.core

private val FIRST_NUMBER = Regex("[0-9]+")

/**
 * Prefixes litteraux du catalogue (jetons, promos). Liste fermee volontairement :
 * l'OCR confond 1 et i, donc « i2i » doit se lire 121 et non prefixe « i » puis 21.
 */
val KNOWN_PREFIXES = listOf("sp", "t", "r")

/**
 * Sets du catalogue. Employes comme PREFERENCE et non comme filtre : un set inconnu
 * dont la forme est plausible est accepte, sans quoi les cartes d une extension
 * future seraient perdues en silence.
 */
private val KNOWN_SETS = listOf("jdg", "ogn", "ogs", "opp", "pr", "sfd", "unl", "ven")

private val SEPARATORS = Regex("[\\s/·•_.,:;|—–]+")
private val DISALLOWED_CHARS = Regex("[^a-z0-9*-]")
private val DASHES = Regex("-+")

private val LETTER_TO_DIGIT = mapOf('o' to '0', 'i' to '1', 'l' to '1', 's' to '5', 'b' to '8', 'z' to '2')
private val DIGIT_TO_LETTER = mapOf('0' to 'o', '1' to 'i', '5' to 's', '8' to 'b')

private fun toDigits(s: String) = s.map { LETTER_TO_DIGIT[it] ?: it }.joinToString("")
private fun toLetters(s: String) = s.map { DIGIT_TO_LETTER[it] ?: it }.joinToString("")
private fun stripLeadingZeros(s: String) = s.trimStart('0').ifEmpty { "0" }

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

private data class Core(val prefix: String, val number: String, val variant: String)

/** Separe un segment central en prefixe litteral, numero et suffixe de variante. */
private fun splitCore(core: String): Core? {
    // Seules lettres de variante presentes au catalogue : a (112), b (8), * (36).
    val lastChar = core.lastOrNull()
    val variant = if (core.length > 1 && lastChar != null && lastChar in "ab*") lastChar.toString() else ""
    val withoutVariant = if (variant.isEmpty()) core else core.dropLast(1)

    val prefix = KNOWN_PREFIXES.firstOrNull { withoutVariant.startsWith(it) } ?: ""
    val digits = toDigits(withoutVariant.substring(prefix.length))
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null

    return Core(prefix, stripLeadingZeros(digits), variant)
}

private data class Candidate(val code: String, val hasTotal: Boolean, val position: Int)

private val AFTER_SET = Regex("^([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:$|-)")
private val SHAPE = Regex("(?:^|-)([a-z0-9]{2,4})-([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:-|$)")

fun parseCollectorCode(rawOcrText: String?): String? {
    if (rawOcrText == null) return null

    val cleaned = rawOcrText.lowercase()
        .replace(SEPARATORS, "-")
        .replace(DISALLOWED_CHARS, "")
        .replace(DASHES, "-")
        .trim('-')
    if (cleaned.isEmpty()) return null

    val knownCandidates = mutableListOf<Candidate>()

    for (set in KNOWN_SETS) {
        // Cherche aussi la graphie que l'OCR produit pour ce set (o lu 0, i lu 1...).
        val spellingsToSearch = mutableSetOf(set)
        spellingsToSearch.add(set.map { c -> DIGIT_TO_LETTER.entries.firstOrNull { it.value == c }?.key ?: c }.joinToString(""))

        for (spelling in spellingsToSearch) {
            var searchPos = cleaned.indexOf(spelling)
            while (searchPos != -1) {
                val beforeIsValid = searchPos == 0 || cleaned[searchPos - 1] == '-'
                val afterIndex = searchPos + spelling.length
                val afterIsValid = afterIndex >= cleaned.length || cleaned[afterIndex] == '-'
                if (beforeIsValid && afterIsValid && afterIndex < cleaned.length) {
                    val afterSet = cleaned.substring(afterIndex + 1)
                    val match = AFTER_SET.find(afterSet)
                    if (match != null) {
                        val core = splitCore(match.groupValues[1])
                        if (core != null) {
                            val totalRaw = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { toDigits(it) }
                            val total = totalRaw?.takeIf { t -> t.all { it.isDigit() } }
                            val head = set + "-" + core.prefix + core.number + core.variant
                            knownCandidates.add(Candidate(if (total != null) "$head-$total" else head, total != null, searchPos))
                        }
                    }
                }
                searchPos = cleaned.indexOf(spelling, searchPos + 1)
            }
        }
    }

    val unknownCandidates = mutableListOf<Candidate>()
    if (knownCandidates.isEmpty()) {
        for (match in SHAPE.findAll(cleaned)) {
            val set = toLetters(match.groupValues[1])
            if (!Regex("^[a-z]{2,4}$").matches(set)) continue
            if (set in KNOWN_SETS) continue
            val core = splitCore(match.groupValues[2]) ?: continue
            // Un set inconnu n'est accepte qu'avec un numero d'au moins deux chiffres :
            // c'est ce qui distingue « rad-012-200 » d'un « deal-2-2 » parasite.
            if (core.number.length < 2) continue
            val totalRaw = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { toDigits(it) }
            val total = totalRaw?.takeIf { t -> t.all { it.isDigit() } }
            val head = set + "-" + core.prefix + core.number + core.variant
            unknownCandidates.add(Candidate(if (total != null) "$head-$total" else head, total != null, match.range.first))
        }
    }

    val allCandidates = if (knownCandidates.isNotEmpty()) knownCandidates else unknownCandidates
    if (allCandidates.isEmpty()) return null

    // Un code complet (avec total) prime ; a defaut, la correspondance la plus tardive.
    return allCandidates.sortedWith(
        compareByDescending<Candidate> { it.hasTotal }.thenByDescending { it.position }
    ).first().code
}
