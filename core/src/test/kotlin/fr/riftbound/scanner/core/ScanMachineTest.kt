package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanMachineTest {

    private val catalogue = Catalog(chargerCatalogueReel())

    private fun rejouer(m: ScanMachine, trames: List<String>, entries: List<Entry> = emptyList()) =
        trames.mapNotNull { m.onFrame(it, "normal", "en", "NM", entries) }

    @Test fun `une seule trame ne suffit pas`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("UNL-121-219")))
    }

    @Test fun `deux trames identiques valident la lecture`() {
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-121-219"))
        assertEquals(1, e.size)
        assertTrue(e[0] is ScanEvent.Accept)
        assertEquals("Bewitching Spirit", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `deux lectures differentes ne valident rien`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-122-219")))
    }

    @Test fun `une carte restee devant l objectif n est pas reemise`() {
        val trames = List(50) { "UNL-121-219" }
        assertEquals(1, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `une trame illisible rearme la machine`() {
        val trames = listOf("UNL-121-219", "UNL-121-219", "", "", "UNL-121-219", "UNL-121-219")
        assertEquals(2, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `deux cartes differentes enchainees sont toutes deux detectees`() {
        val trames = listOf("UNL-121-219", "UNL-121-219", "OPP-259-298", "OPP-259-298")
        assertEquals(2, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `un code deja en collection remonte un doublon`() {
        val carte = catalogue.matchCards("unl-121-219")[0]
        val (entries, _) = addScan(emptyList(), carte, carte.code, "normal", "en", "NM", "t")
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-121-219"), entries)
        assertTrue(e[0] is ScanEvent.Duplicate)
        assertEquals(0, (e[0] as ScanEvent.Duplicate).index)
    }

    @Test fun `la finition tranche un couple Metal sans interrompre le scan`() {
        val m = ScanMachine(catalogue)
        val e = listOf("OPP-259-298", "OPP-259-298").mapNotNull {
            m.onFrame(it, "metal", "en", "NM", emptyList())
        }
        assertEquals("Yasuo - Unforgiven (Metal)", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `un code inconnu remonte les candidats proches`() {
        // "UNL-122-219" existe reellement dans le catalogue (Crescent Guardian) : on
        // prend un total invente ("220") pour obtenir un code absent du catalogue,
        // mais toujours voisin de Bewitching Spirit par la distance d'edition.
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-121-220", "UNL-121-220"))
        assertTrue(e[0] is ScanEvent.Unknown)
        val inconnu = e[0] as ScanEvent.Unknown
        assertEquals("unl-121-220", inconnu.code)
        assertTrue(inconnu.candidates.any { it.name == "Bewitching Spirit" })
    }

    @Test fun `un texte illisible ne produit rien`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("Jonathan Santoro", "Illustration")))
    }

    @Test fun `decide tranche immediatement sans regle des deux trames`() {
        val v = ScanMachine(catalogue).decide("unl-121-219", "normal", "en", "NM", emptyList())
        assertTrue(v is ScanEvent.Accept)
    }

    @Test fun `reset oublie la lecture en cours`() {
        val m = ScanMachine(catalogue)
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.reset()
        assertNull(m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList()))
    }
}
