package fr.riftbound.scanner.core

sealed interface ScanEvent {
    data class Accept(val code: String, val card: Card) : ScanEvent
    data class Duplicate(val code: String, val card: Card, val index: Int) : ScanEvent
    data class Ambiguous(val code: String, val cards: List<Card>) : ScanEvent
    data class Unknown(val code: String, val candidates: List<Card>) : ScanEvent
}

/**
 * Aiguillage du flux de scan a partir d'une suite de textes. Module pur : ni camera,
 * ni Android. C'est ce qui permet de tester le flux complet en rejouant des trames.
 */
class ScanMachine(private val catalog: Catalog, private val confirmFrames: Int = 2) {

    private var pending: String? = null
    private var streak = 0
    private var emitted: String? = null

    fun reset() {
        pending = null
        streak = 0
        emitted = null
    }

    /** Verdict immediat pour un code deja certain : employe par la saisie manuelle. */
    fun decide(
        code: String, finish: String, language: String, condition: String, entries: List<Entry>
    ): ScanEvent {
        val cards = catalog.matchCards(code)
        if (cards.isEmpty()) return ScanEvent.Unknown(code, catalog.candidatesFor(code))

        return when (val resolved = resolveVariant(cards, finish)) {
            is Resolution.Ambiguous -> ScanEvent.Ambiguous(code, resolved.cards)
            is Resolution.Unique -> {
                val card = resolved.card
                // `identityKey` est la meme fonction qu'utilise `entryKey` : si son
                // calcul evolue, les deux appelants evoluent ensemble, sans divergence
                // silencieuse (bug reel : une carte Metal absorbee par la ligne normale).
                val key = identityKey(card.riftcodexId, finish, language, condition)
                val existingIndex = entries.indexOfFirst { entryKey(it) == key }
                if (existingIndex != -1) ScanEvent.Duplicate(code, card, existingIndex)
                else ScanEvent.Accept(code, card)
            }
        }
    }

    fun onFrame(
        rawText: String?, finish: String, language: String, condition: String, entries: List<Entry>
    ): ScanEvent? {
        val code = parseCollectorCode(rawText)

        if (code == null) {
            // Plus rien de lisible : la carte a quitte le cadre, on rearme.
            reset()
            return null
        }

        if (code != pending) {
            pending = code
            streak = 1
            return null
        }

        streak += 1
        if (streak < confirmFrames) return null
        if (emitted == code) return null

        // `emitted` n'est fige qu'apres un verdict reussi, pour qu'une exception
        // ne fasse pas disparaitre la carte en silence.
        val result = decide(code, finish, language, condition, entries)
        emitted = code
        return result
    }
}
