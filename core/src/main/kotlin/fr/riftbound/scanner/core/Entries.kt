package fr.riftbound.scanner.core

/** Une ligne de collection : un exemplaire vendable sous une meme annonce. */
data class Entry(
    val code: String,
    val name: String,
    val set: String,
    val setLabel: String,
    val number: Int?,
    val rarity: String,
    val type: String,
    val domain: List<String>,
    val riftcodexId: String?,
    val tcgplayerId: String?,
    val variant: String?,
    val finish: String,
    val language: String,
    val condition: String,
    val quantity: Int,
    val rawCode: String,
    val scannedAt: String,
    val unknown: Boolean
)

sealed interface ScanOutcome {
    data class Added(val index: Int) : ScanOutcome
    data class Duplicate(val index: Int) : ScanOutcome
}

/**
 * La cle se fonde sur l'identifiant intrinseque de la carte, et non sur le code
 * imprime : seize codes designent deux cartes distinctes, qui ne doivent pas fusionner.
 */
fun entryKey(entry: Entry): String =
    listOf(entry.riftcodexId ?: entry.rawCode, entry.finish, entry.language, entry.condition)
        .joinToString("|")

private fun entryFromScan(
    card: Card?, rawCode: String, finish: String,
    language: String, condition: String, scannedAt: String
): Entry = if (card == null) {
    Entry(
        code = rawCode, name = "", set = "", setLabel = "", number = null,
        rarity = "", type = "", domain = emptyList(), riftcodexId = null, tcgplayerId = null,
        variant = null, finish = finish, language = language, condition = condition,
        quantity = 1, rawCode = rawCode, scannedAt = scannedAt, unknown = true
    )
} else {
    Entry(
        code = card.code, name = card.name, set = card.set, setLabel = card.setLabel,
        number = card.number, rarity = card.rarity, type = card.type,
        // Copie defensive : le catalogue est partage par toute la session.
        domain = card.domain.toList(),
        riftcodexId = card.riftcodexId, tcgplayerId = card.tcgplayerId,
        variant = variantOf(card.name), finish = finish, language = language,
        condition = condition, quantity = 1, rawCode = rawCode,
        scannedAt = scannedAt, unknown = false
    )
}

/**
 * N'incremente jamais d'elle-meme : un doublon est signale et l'utilisateur tranche.
 * C'est ce qui evite qu'une carte restee devant l'objectif s'ajoute en boucle.
 */
fun addScan(
    entries: List<Entry>, card: Card?, rawCode: String, finish: String,
    language: String, condition: String, scannedAt: String
): Pair<List<Entry>, ScanOutcome> {
    val entry = entryFromScan(card, rawCode, finish, language, condition, scannedAt)
    val key = entryKey(entry)
    val index = entries.indexOfFirst { entryKey(it) == key }
    return if (index != -1) entries to ScanOutcome.Duplicate(index)
    else (entries + entry) to ScanOutcome.Added(entries.size)
}

fun incrementEntry(entries: List<Entry>, index: Int): List<Entry> =
    entries.mapIndexed { i, e -> if (i == index) e.copy(quantity = e.quantity + 1) else e }

fun updateEntry(
    entries: List<Entry>, index: Int,
    language: String? = null, condition: String? = null, quantity: Int? = null
): List<Entry> = entries.mapIndexed { i, e ->
    if (i != index) e
    else e.copy(
        language = language ?: e.language,
        condition = condition ?: e.condition,
        quantity = quantity ?: e.quantity
    )
}

fun removeEntry(entries: List<Entry>, index: Int): List<Entry> =
    entries.filterIndexed { i, _ -> i != index }
