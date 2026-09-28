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
        val frames = listOf("UNL-121-219", "UNL-121-219", "", "", "UNL-121-219", "UNL-121-219")
        assertEquals(2, replay(ScanMachine(catalog), frames).size)
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
}
