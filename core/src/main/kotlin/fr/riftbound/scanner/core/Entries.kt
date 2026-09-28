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
 * Coeur du calcul de cle, partage par `entryKey` et par la machine de scan : une
 * seule notion d'identite (carte + finition + langue + etat) sert partout, pour
 * qu'une carte Metal ne puisse plus jamais etre absorbee par la ligne normale.
 */
internal fun identityKey(identity: String, finish: String, language: String, condition: String): String =
    listOf(identity, finish, language, condition).joinToString("|")

/**
 * La cle se fonde sur l'identifiant intrinseque de la carte, et non sur le code
 * imprime : seize codes designent deux cartes distinctes, qui ne doivent pas fusionner.
 */
fun entryKey(entry: Entry): String =
    identityKey(entry.riftcodexId ?: entry.rawCode, entry.finish, entry.language, entry.condition)

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

/**
 * Ce qui s'est passe pour la carte commise : soit une nouvelle ligne
 * (`Added`), soit l'incrementation d'une ligne existante (`Incremented`,
 * qui porte la nouvelle quantite). Porter cette distinction dans le type de
 * retour est ce qui rend l'oubli de l'incrementation impossible a exprimer
 * pour l'appelant : il ne peut pas ignorer un cas qu'il doit forcement
 * discriminer pour formuler son message.
 */
sealed interface CommitResult {
    data class Added(val entries: List<Entry>, val index: Int) : CommitResult
    data class Incremented(val entries: List<Entry>, val index: Int, val quantity: Int) : CommitResult
}

/**
 * Point unique de decision : une carte choisie ou reconnue - par scan
 * direct, confirmation de doublon ou choix d'une variante depuis un
 * dialogue - doit entrer dans la collection, quelle qu'en soit la maniere.
 * `addScan` seul ne suffit pas : il signale un doublon sans jamais
 * l'incrementer lui-meme (voir sa doc), ce qui a deja ete a l'origine d'un
 * choix de variante qui, une fois confirme, ne changeait rien du tout.
 * `commitCard` ferme cette possibilite en effectuant lui-meme
 * l'incrementation quand c'en est un, si bien qu'aucun appelant ne peut
 * plus l'oublier.
 */
fun commitCard(
    entries: List<Entry>, card: Card?, rawCode: String, finish: String,
    language: String, condition: String, scannedAt: String
): CommitResult {
    val (afterAdd, outcome) = addScan(entries, card, rawCode, finish, language, condition, scannedAt)
    return when (outcome) {
        is ScanOutcome.Added -> CommitResult.Added(afterAdd, outcome.index)
        is ScanOutcome.Duplicate -> {
            val incremented = incrementEntry(entries, outcome.index)
            CommitResult.Incremented(incremented, outcome.index, incremented[outcome.index].quantity)
        }
    }
}

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
