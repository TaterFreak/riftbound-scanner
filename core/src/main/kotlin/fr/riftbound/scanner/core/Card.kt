package fr.riftbound.scanner.core

/** Une carte du catalogue, telle qu'embarquee dans l'application. */
data class Card(
    val code: String,
    val riftcodexId: String,
    val tcgplayerId: String?,
    val name: String,
    val set: String,
    val setLabel: String,
    val number: Int?,
    val rarity: String,
    val type: String,
    val domain: List<String>
)

private val FIRST_NUMBER_SEGMENT = Regex("[0-9]+")

/** Le numero de collection, c'est le segment du MILIEU, pas le dernier (taille du set). */
private fun numberOf(code: String): Int {
    val parts = code.split("-")
    if (parts.size < 2) return 0
    return FIRST_NUMBER_SEGMENT.find(parts[1])?.value?.toIntOrNull() ?: 0
}

private fun distance(a: String, b: String): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > 2) return 3
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}

/**
 * Index du catalogue. Un code peut legitimement designer plusieurs cartes : seize
 * codes opposent une carte normale a sa version Metal.
 */
class Catalog(val cards: List<Card>) {

    private val index: Map<String, List<Card>> =
        cards.groupBy { canonicalCode(it.code) ?: it.code }

    fun matchCards(code: String?): List<Card> {
        val key = canonicalCode(code) ?: return emptyList()
        return index[key] ?: emptyList()
    }

    /**
     * Les cartes dont le code est le plus proche, pour l'ecran de lecture douteuse.
     *
     * `code` nul : durcissement volontaire par rapport a l'implementation JavaScript
     * d'origine, qui levait une TypeError sur une entree nulle. Ici, aucune exception :
     * liste vide, conformement a la regle produit « ne jamais perdre un scan ». (Une
     * chaine vide n'emprunte pas cette branche mais aboutit aussi a une liste vide :
     * sa distance a toute cle du catalogue depasse 2 et est filtree plus bas.)
     */
    fun candidatesFor(code: String?, limit: Int = 5): List<Card> {
        val target = canonicalCode(code) ?: return emptyList()
        val targetNumber = numberOf(target)
        return index.keys
            .map { key -> Triple(key, distance(target, key), kotlin.math.abs(numberOf(key) - targetNumber)) }
            .filter { it.second in 1..2 }
            // Le troisieme critere (la cle elle-meme) n'a aucune signification metier :
            // il ne sert qu'a rendre l'ordre stable et reproductible quand deux cles sont
            // a egalite de distance et de proximite numerique (ex. un suffixe de variante
            // '*' face a 'a'). La comparaison ordinale (String.compareTo) est preferee a
            // une comparaison sensible a la locale (type localeCompare) precisement parce
            // qu'elle est deterministe et independante de la machine qui l'execute.
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            .take(limit)
            .flatMap { index[it.first].orEmpty() }
    }
}
