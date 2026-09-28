package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanMachineTest {

    private val catalog = Catalog(loadRealCatalog())

    private fun replay(m: ScanMachine, frames: List<String>, entries: List<Entry> = emptyList()) =
        frames.mapNotNull { m.onFrame(it, "normal", "en", "NM", entries) }

    @Test fun `une seule trame ne suffit pas`() {
        assertEquals(emptyList(), replay(ScanMachine(catalog), listOf("UNL-121-219")))
    }

    @Test fun `deux trames identiques valident la lecture`() {
        val e = replay(ScanMachine(catalog), listOf("UNL-121-219", "UNL-121-219"))
        assertEquals(1, e.size)
        assertTrue(e[0] is ScanEvent.Accept)
        assertEquals("Bewitching Spirit", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `deux lectures differentes ne valident rien`() {
        assertEquals(emptyList(), replay(ScanMachine(catalog), listOf("UNL-121-219", "UNL-122-219")))
    }

    @Test fun `une carte restee devant l objectif n est pas reemise`() {
        val frames = List(50) { "UNL-121-219" }
        assertEquals(1, replay(ScanMachine(catalog), frames).size)
    }

    @Test fun `une trame illisible rearme la machine`() {
        // Delai explicitement nul : ce test date d'avant l'introduction du
        // delai par code et ne doit pas en dependre pour garder son sens
        // (voir aussi le test dedie plus bas qui verifie ce meme delai nul).
        val frames = listOf("UNL-121-219", "UNL-121-219", "", "", "UNL-121-219", "UNL-121-219")
        assertEquals(2, replay(ScanMachine(catalog, cooldownMs = 0), frames).size)
    }

    @Test fun `deux cartes differentes enchainees sont toutes deux detectees`() {
        val frames = listOf("UNL-121-219", "UNL-121-219", "OPP-259-298", "OPP-259-298")
        assertEquals(2, replay(ScanMachine(catalog), frames).size)
    }

    @Test fun `un code deja en collection remonte un doublon`() {
        val card = catalog.matchCards("unl-121-219")[0]
        val (entries, _) = addScan(emptyList(), card, card.code, "normal", "en", "NM", "t")
        val e = replay(ScanMachine(catalog), listOf("UNL-121-219", "UNL-121-219"), entries)
        assertTrue(e[0] is ScanEvent.Duplicate)
        assertEquals(0, (e[0] as ScanEvent.Duplicate).index)
    }

    @Test fun `la finition tranche un couple Metal sans interrompre le scan`() {
        val m = ScanMachine(catalog)
        val e = listOf("OPP-259-298", "OPP-259-298").mapNotNull {
            m.onFrame(it, "metal", "en", "NM", emptyList())
        }
        assertEquals("Yasuo - Unforgiven (Metal)", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `un code inconnu remonte les candidats proches`() {
        // "UNL-122-219" existe reellement dans le catalogue (Crescent Guardian) : on
        // prend un total invente ("220") pour obtenir un code absent du catalogue,
        // mais toujours voisin de Bewitching Spirit par la distance d'edition.
        val e = replay(ScanMachine(catalog), listOf("UNL-121-220", "UNL-121-220"))
        assertTrue(e[0] is ScanEvent.Unknown)
        val unknownEvent = e[0] as ScanEvent.Unknown
        assertEquals("unl-121-220", unknownEvent.code)
        assertTrue(unknownEvent.candidates.any { it.name == "Bewitching Spirit" })
    }

    @Test fun `un texte illisible ne produit rien`() {
        assertEquals(emptyList(), replay(ScanMachine(catalog), listOf("Jonathan Santoro", "Illustration")))
    }

    @Test fun `decide tranche immediatement sans regle des deux trames`() {
        val v = ScanMachine(catalog).decide("unl-121-219", "normal", "en", "NM", emptyList())
        assertTrue(v is ScanEvent.Accept)
    }

    @Test fun `reset oublie la lecture en cours`() {
        val m = ScanMachine(catalog)
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.reset()
        assertNull(m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList()))
    }

    @Test fun `une image par bloc plutot qu un onFrame par bloc laisse le code s accumuler`() {
        // Bug reel : en appelant onFrame pour chaque bloc reconnu sur une image (nom,
        // regles, illustrateur, code), un bloc sans code rearmait la machine avant que
        // le code n ait pu s accumuler sur deux images : aucun evenement n etait jamais
        // emis. La correction choisit un seul texte par image avec pickCodeText, puis
        // appelle onFrame une unique fois avec ce texte.
        val image1 = listOf("Bewitching Spirit", "Deal -2/-2 to a unit.", "Wild Blue Studios", "UNL-121-219")
        val image2 = listOf("Bewitching Spirit", "Deal -2/-2 to a unit.", "Wild Blue Studios", "UNL-121-219")
        val m = ScanMachine(catalog)
        val events = listOf(image1, image2).mapNotNull { blocks ->
            m.onFrame(pickCodeText(blocks), "normal", "en", "NM", emptyList())
        }
        assertEquals(1, events.size)
        assertTrue(events[0] is ScanEvent.Accept)
    }

    @Test fun `une exception levee par decide n empeche pas la trame suivante d emettre`() {
        // Bug reel vise : si `emitted` etait fige avant l'appel a `decide`, une
        // exception rendrait la carte invisible pour toujours tant qu'elle reste
        // dans le champ. Ici, une finition invalide fait lever `resolveVariant`
        // sur la deuxieme trame ; la troisieme, meme code, sans trame illisible
        // entre les deux, doit malgre tout produire un evenement.
        val m = ScanMachine(catalog)
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertFailsWith<IllegalArgumentException> {
            m.onFrame("UNL-121-219", "foil", "en", "NM", emptyList())
        }
        val e = m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertTrue(e is ScanEvent.Accept)
    }

    // Delai par code : sans lui, une carte qu'on retire lentement du cadre
    // produit une image intermediaire illisible qui rearme la machine
    // (reset()), et la carte, encore partiellement visible, est relue et
    // validee une seconde fois. Le delai ignore un code deja valide pendant
    // `cooldownMs`, mais n'affecte jamais un code different : la cadence de
    // lecture d'une pile de cartes distinctes reste intacte.

    @Test fun `un code valide n est pas repris pendant son delai, meme apres une trame illisible`() {
        var currentTime = 0L
        val m = ScanMachine(catalog, cooldownMs = 1500, now = { currentTime })
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        val first = m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertTrue(first is ScanEvent.Accept)

        // La carte quitte le cadre : une trame illisible rearme pending/streak/emitted.
        m.onFrame("", "normal", "en", "NM", emptyList())
        // A peine 500 ms plus tard, bien avant l'expiration du delai de 1500 ms.
        currentTime += 500
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        val second = m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertNull(second)
    }

    @Test fun `un code valide est de nouveau accepte une fois le delai ecoule`() {
        var currentTime = 0L
        val m = ScanMachine(catalog, cooldownMs = 1500, now = { currentTime })
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())

        m.onFrame("", "normal", "en", "NM", emptyList())
        currentTime += 1500 // delai ecoule
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        val second = m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertTrue(second is ScanEvent.Accept)
    }

    @Test fun `un code different n est jamais soumis au delai d un autre code`() {
        var currentTime = 0L
        val m = ScanMachine(catalog, cooldownMs = 1500, now = { currentTime })
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())

        // Meme instant, code different : le delai du premier ne le concerne pas.
        m.onFrame("OPP-259-298", "normal", "en", "NM", emptyList())
        val second = m.onFrame("OPP-259-298", "normal", "en", "NM", emptyList())
        assertTrue(second is ScanEvent.Accept)
    }

    @Test fun `le delai survit a un reset explicite`() {
        // Choix assume : reset() efface la lecture en cours (pending, streak,
        // emitted) mais jamais le delai par code. reset() est declenche par
        // chaque trame illisible pendant qu'une carte quitte le cadre : si le
        // delai en dependait, il serait annule au moment precis ou il doit
        // agir - ce serait exactement le bug que ce delai corrige. reset()
        // est aussi appele quand l'utilisateur ferme un dialogue de decision
        // (Doublon, Variante ambigue, Code inconnu) : par ce meme choix, un
        // rescan immediat du meme code juste apres reste donc ignore jusqu'a
        // expiration du delai. Cote produit, un rescan aussi rapproche est
        // plus probablement un residu de cadrage (la carte reste devant
        // l'objectif pendant que le dialogue etait ouvert) qu'une nouvelle
        // intention deliberee.
        var currentTime = 0L
        val m = ScanMachine(catalog, cooldownMs = 1500, now = { currentTime })
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())

        m.reset() // simule la fermeture d'un dialogue de decision
        currentTime += 500 // bien avant l'expiration du delai
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        val second = m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        assertNull(second)
    }

    @Test fun `un delai de zero retrouve le comportement d avant l introduction du delai`() {
        val frames = listOf("UNL-121-219", "UNL-121-219", "", "", "UNL-121-219", "UNL-121-219")
        assertEquals(2, replay(ScanMachine(catalog, cooldownMs = 0), frames).size)
    }
}
