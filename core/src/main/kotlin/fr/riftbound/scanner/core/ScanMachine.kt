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
 *
 * `cooldownMs` et `now` portent le delai par code : une fois un code valide
 * (un evenement emis pour lui), il est ignore pendant `cooldownMs`, meme si
 * une trame illisible rearme la machine entre-temps - c'est precisement le
 * cas d'une carte qu'on retire lentement du cadre, relue et validee une
 * seconde fois avant d'avoir completement disparu. Un code different n'est
 * jamais concerne par le delai d'un autre : la cadence de lecture d'une pile
 * de cartes distinctes n'est pas affectee. `now` est injectable pour que les
 * tests controlent le temps sans jamais attendre ; sa valeur par defaut
 * s'appuie sur l'horloge systeme, seul point ou une horloge est lue en dur.
 */
class ScanMachine(
    private val catalog: Catalog,
    private val confirmFrames: Int = 2,
    private val cooldownMs: Long = 1500,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var pending: String? = null
    private var streak = 0
    private var emitted: String? = null

    // Survit deliberement a reset() : voir la doc de la classe. reset() est
    // appele a chaque trame illisible (la carte quitte le cadre) et aussi
    // quand l'utilisateur ferme un dialogue de decision (Doublon, Variante
    // ambigue, Code inconnu). Si ce delai etait efface par reset(), la toute
    // premiere situation qu'il doit couvrir - une trame illisible survenant
    // pendant qu'une carte quitte lentement le cadre - annulerait la
    // protection au moment precis ou elle sert. Consequence assumee cote
    // produit : fermer un dialogue puis rescanner aussitot la meme carte
    // reste ignore jusqu'a expiration du delai, ce qui est juge plus
    // probable comme residu de cadrage que comme nouvelle intention
    // deliberee. La saisie manuelle (`decide`, appelee directement) n'est en
    // revanche jamais soumise a ce delai.
    private val lastValidatedAt = mutableMapOf<String, Long>()

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

        // Delai par code : voir la doc de la classe. Verifie avant `decide`
        // pour qu'un code encore dans son delai ne soit meme pas relance.
        val lastValidation = lastValidatedAt[code]
        if (lastValidation != null && now() - lastValidation < cooldownMs) return null

        // `emitted` et le delai ne sont figes qu'apres un verdict reussi,
        // pour qu'une exception ne fasse pas disparaitre la carte en silence.
        val result = decide(code, finish, language, condition, entries)
        emitted = code
        lastValidatedAt[code] = now()
        return result
    }
}
