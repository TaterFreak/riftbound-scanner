package fr.riftbound.scanner.core

private val SUFFIX = Regex("\\(([^()]+)\\)$")

/** Le suffixe entre parentheses en fin de nom, seul marqueur de variante du catalogue. */
fun variantOf(name: String?): String? =
    name?.let { SUFFIX.find(it)?.groupValues?.get(1) }

sealed interface Resolution {
    data class Unique(val card: Card) : Resolution
    data class Ambiguous(val cards: List<Card>) : Resolution
}

private fun isMetal(card: Card) = variantOf(card.name) == "Metal"

/**
 * Le reglage de finition tranche les seize codes ambigus Normale/Metal du catalogue.
 * Toute autre ambiguite est rendue a l'utilisateur plutot que devinee.
 */
fun resolveVariant(cards: List<Card>, finish: String): Resolution {
    require(finish == "normal" || finish == "metal") {
        "finish doit valoir 'normal' ou 'metal', recu : $finish"
    }
    if (cards.size == 1) return Resolution.Unique(cards[0])
    val wanted = if (finish == "metal") cards.filter(::isMetal) else cards.filterNot(::isMetal)
    return if (wanted.size == 1) Resolution.Unique(wanted[0]) else Resolution.Ambiguous(cards)
}
