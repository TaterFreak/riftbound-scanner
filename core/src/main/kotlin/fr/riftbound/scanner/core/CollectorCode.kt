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

/**
 * `matchEnd` est la position, dans la chaine nettoyee, juste apres la fin de la
 * correspondance (total inclus s'il y en a un). Elle sert a localiser ce qui suit
 * immediatement le code - notamment le code de langue imprime a sa droite,
 * voir `parsePrintedLanguage` - sans avoir a rechercher une seconde fois.
 */
private data class Candidate(val code: String, val hasTotal: Boolean, val position: Int, val matchEnd: Int)

private val AFTER_SET = Regex("^([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:$|-)")
private val SHAPE = Regex("(?:^|-)([a-z0-9]{2,4})-([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:-|$)")

private fun cleanOcrText(rawOcrText: String): String =
    rawOcrText.lowercase()
        .replace(SEPARATORS, "-")
        .replace(DISALLOWED_CHARS, "")
        .replace(DASHES, "-")
        .trim('-')

/** Rassemble tous les codes plausibles trouves dans une chaine deja nettoyee. */
private fun findCandidates(cleaned: String): List<Candidate> {
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
                            val matchEnd = afterIndex + 1 + match.range.last + 1
                            knownCandidates.add(
                                Candidate(if (total != null) "$head-$total" else head, total != null, searchPos, matchEnd)
                            )
                        }
                    }
                }
                searchPos = cleaned.indexOf(spelling, searchPos + 1)
            }
        }
    }

    if (knownCandidates.isNotEmpty()) return knownCandidates

    val unknownCandidates = mutableListOf<Candidate>()
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
        unknownCandidates.add(
            Candidate(if (total != null) "$head-$total" else head, total != null, match.range.first, match.range.last + 1)
        )
    }
    return unknownCandidates
}

/** Le candidat retenu : un code complet (avec total) prime ; a defaut, la correspondance la plus tardive. */
private fun winningCandidate(cleaned: String): Candidate? =
    findCandidates(cleaned).maxWithOrNull(
        compareBy<Candidate> { it.hasTotal }.thenBy { it.position }
    )

fun parseCollectorCode(rawOcrText: String?): String? {
    if (rawOcrText == null) return null
    val cleaned = cleanOcrText(rawOcrText)
    if (cleaned.isEmpty()) return null
    return winningCandidate(cleaned)?.code
}

/**
 * Codes de langue imprimes au catalogue, format ISO 639-1 deux lettres. Liste
 * couvrant les langues usuelles d'un jeu de cartes distribue mondialement
 * (Riftbound est edite par Riot Games) ; a completer si une carte imprimee
 * dans une langue absente d'ici est rencontree.
 */
private val KNOWN_LANGUAGES = setOf("en", "fr", "de", "es", "it", "pt", "ja", "ko", "zh", "ru", "pl", "tr")

private val LANGUAGE_TOKEN = Regex("^([a-z]{2})(?:$|-)")

/**
 * Lit le code de langue imprime immediatement apres le code de collection, sur
 * la meme ligne (ex. « UNL · 070/219 · FR » -> "fr"). Reutilise la localisation
 * du code deja calculee par `winningCandidate` plutot que de rechercher une
 * seconde fois : la langue n'est reconnue que collee au code, jamais un jeton
 * de deux lettres trouve ailleurs sur la carte (texte de regles, illustrateur).
 *
 * Volontairement conservateur : un jeton absent de `KNOWN_LANGUAGES`, ou
 * separe du code par autre chose, renvoie null plutot que de risquer une
 * etiquette de langue erronee - l'utilisateur peut toujours la corriger a la
 * main, alors qu'une erreur silencieuse passerait inapercue.
 */
fun parsePrintedLanguage(rawOcrText: String?): String? {
    if (rawOcrText == null) return null
    val cleaned = cleanOcrText(rawOcrText)
    if (cleaned.isEmpty()) return null
    val candidate = winningCandidate(cleaned) ?: return null
    val remainder = cleaned.substring(candidate.matchEnd)
    val token = LANGUAGE_TOKEN.find(remainder)?.groupValues?.get(1) ?: return null
    return token.takeIf { it in KNOWN_LANGUAGES }
}

/**
 * Choisit, parmi les blocs de texte reconnus sur une meme image, celui qui porte le
 * code de collection. Une carte produit plusieurs blocs (nom, texte de regles,
 * illustrateur, code) : n'en retenir qu'un seul evite qu'un bloc sans code ne
 * rearme la machine de scan entre deux blocs qui, eux, portent le meme code.
 */
fun pickCodeText(blocks: List<String>): String? =
    blocks.firstOrNull { parseCollectorCode(it) != null }
