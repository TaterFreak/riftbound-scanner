package fr.riftbound.scanner.core

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Charge le vrai catalogue depuis data/cards.json, a la racine du depot. */
internal fun chargerCatalogueReel(): List<Card> {
    val fichier = File("../data/cards.json")
    val racine = JSONObject(fichier.readText())
    val tableau = racine.getJSONArray("cards")
    return (0 until tableau.length()).map { i ->
        val o = tableau.getJSONObject(i)
        val domaines = o.getJSONArray("domain")
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
            domain = (0 until domaines.length()).map { domaines.getString(it) }
        )
    }
}

class CatalogTest {

    private val cartes = chargerCatalogueReel()
    private val catalogue = Catalog(cartes)

    @Test
    fun `le catalogue reel contient 1320 cartes`() {
        assertEquals(1320, cartes.size)
    }

    @Test
    fun `parseCollectorCode relit les 1320 codes du catalogue`() {
        val mauvais = cartes.filter { parseCollectorCode(it.code) != canonicalCode(it.code) }
        assertEquals(emptyList(), mauvais.map { it.code })
    }

    @Test
    fun `matchCards retrouve une carte unique`() {
        val trouvees = catalogue.matchCards("unl-121-219")
        assertEquals(1, trouvees.size)
        assertEquals("Bewitching Spirit", trouvees[0].name)
    }

    @Test
    fun `matchCards retrouve les deux cartes d un couple Metal`() {
        assertEquals(2, catalogue.matchCards("opp-259-298").size)
    }

    @Test
    fun `seize codes du catalogue designent deux cartes`() {
        val ambigus = cartes.groupBy { canonicalCode(it.code) }.filterValues { it.size > 1 }
        assertEquals(16, ambigus.size)
        assertTrue(ambigus.values.all { groupe -> groupe.any { it.name.endsWith("(Metal)") } })
    }

    @Test
    fun `matchCards ignore les zeros de tete et la casse`() {
        assertEquals(1, catalogue.matchCards("UNL-029a-219").size)
        assertEquals(1, catalogue.matchCards("unl-29a-219").size)
    }

    @Test
    fun `matchCards renvoie une liste vide pour un code inconnu`() {
        assertEquals(emptyList(), catalogue.matchCards("zzz-999-999"))
        assertEquals(emptyList(), catalogue.matchCards(null))
    }

    @Test
    fun `candidatesFor propose le voisin numerique le plus proche en tete`() {
        val proches = catalogue.candidatesFor("unl-122-219").map { canonicalCode(it.code) }
        assertEquals("unl-121-219", proches.first())
    }

    @Test
    fun `candidatesFor respecte la limite par defaut de cinq`() {
        assertTrue(catalogue.candidatesFor("unl-122-219").size <= 5)
        assertTrue(catalogue.candidatesFor("unl-122-219", limit = 2).size <= 2)
    }

    @Test
    fun `candidatesFor ne propose rien au-dela de la distance deux`() {
        assertEquals(emptyList(), catalogue.candidatesFor("abc-999-111"))
    }
}
