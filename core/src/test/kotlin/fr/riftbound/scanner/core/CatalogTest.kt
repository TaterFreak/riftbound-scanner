package fr.riftbound.scanner.core

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Charge le vrai catalogue depuis data/cards.json, a la racine du depot. */
internal fun loadRealCatalog(): List<Card> {
    val file = File("../data/cards.json")
    val root = JSONObject(file.readText())
    val array = root.getJSONArray("cards")
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val domains = o.getJSONArray("domain")
        Card(
            code = o.getString("code"),
            riftcodexId = o.getString("riftcodexId"),
            tcgplayerId = if (o.isNull("tcgplayerId")) null else o.getString("tcgplayerId"),
            name = o.getString("name"),
            set = o.getString("set"),
            setLabel = o.getString("setLabel"),
            number = if (o.isNull("number")) null else o.getInt("number"),
            rarity = o.getString("rarity"),
            type = o.getString("type"),
            domain = (0 until domains.length()).map { domains.getString(it) }
        )
    }
}

class CatalogTest {

    private val cards = loadRealCatalog()
    private val catalog = Catalog(cards)

    @Test
    fun `le catalogue reel contient 1320 cartes`() {
        assertEquals(1320, cards.size)
    }

    @Test
    fun `parseCollectorCode relit les 1320 codes du catalogue`() {
        val mismatches = cards.filter { parseCollectorCode(it.code) != canonicalCode(it.code) }
        assertEquals(emptyList(), mismatches.map { it.code })
    }

    @Test
    fun `matchCards retrouve une carte unique`() {
        val found = catalog.matchCards("unl-121-219")
        assertEquals(1, found.size)
        assertEquals("Bewitching Spirit", found[0].name)
    }

    @Test
    fun `matchCards retrouve les deux cartes d un couple Metal`() {
        assertEquals(2, catalog.matchCards("opp-259-298").size)
    }

    @Test
    fun `seize codes du catalogue designent deux cartes`() {
        val ambiguous = cards.groupBy { canonicalCode(it.code) }.filterValues { it.size > 1 }
        assertEquals(16, ambiguous.size)
        assertTrue(ambiguous.values.all { group -> group.any { it.name.endsWith("(Metal)") } })
    }

    @Test
    fun `matchCards ignore les zeros de tete et la casse`() {
        assertEquals(1, catalog.matchCards("UNL-029a-219").size)
        assertEquals(1, catalog.matchCards("unl-29a-219").size)
    }

    @Test
    fun `matchCards renvoie une liste vide pour un code inconnu`() {
        assertEquals(emptyList(), catalog.matchCards("zzz-999-999"))
        assertEquals(emptyList(), catalog.matchCards(null))
    }

    @Test
    fun `candidatesFor propose le voisin numerique le plus proche en tete`() {
        val neighbors = catalog.candidatesFor("unl-122-219").map { canonicalCode(it.code) }
        assertEquals("unl-121-219", neighbors.first())
    }

    @Test
    fun `candidatesFor respecte la limite par defaut de cinq`() {
        assertTrue(catalog.candidatesFor("unl-122-219").size <= 5)
        assertTrue(catalog.candidatesFor("unl-122-219", limit = 2).size <= 2)
    }

    @Test
    fun `candidatesFor ne propose rien au-dela de la distance deux`() {
        assertEquals(emptyList(), catalog.candidatesFor("abc-999-111"))
    }

    @Test
    fun `candidatesFor renvoie une liste vide pour une entree nulle ou vide`() {
        // Durcissement assume par rapport a l'implementation JavaScript d'origine, qui
        // levait une TypeError sur une entree nulle : ici, jamais d'exception.
        assertEquals(emptyList(), catalog.candidatesFor(null))
        assertEquals(emptyList(), catalog.candidatesFor(""))
    }

    @Test
    fun `candidatesFor depart les ex aequo par comparaison ordinale (cas fige)`() {
        // Deux cartes dont la cle ne differe que par un suffixe de variante : meme
        // distance d'edition a la cible (1, par insertion) et meme numero (12), donc
        // a egalite sur les deux premiers criteres. Seule la comparaison ordinale des
        // cles les depart : '*' (0x2A) precede 'a' (0x61), la carte a suffixe '*' doit
        // donc sortir en tete. Ordre calcule a la main avant d'etre fige ici.
        val starCard = Card(
            code = "abc-12*-200", riftcodexId = "r1", tcgplayerId = null,
            name = "Carte etoile", set = "abc", setLabel = "Abc", number = 12,
            rarity = "Commune", type = "Unite", domain = emptyList()
        )
        val cardA = Card(
            code = "abc-12a-200", riftcodexId = "r2", tcgplayerId = null,
            name = "Carte a", set = "abc", setLabel = "Abc", number = 12,
            rarity = "Commune", type = "Unite", domain = emptyList()
        )
        val frozenCatalog = Catalog(listOf(cardA, starCard))

        assertEquals(listOf(starCard, cardA), frozenCatalog.candidatesFor("abc-12-200"))
    }
}
