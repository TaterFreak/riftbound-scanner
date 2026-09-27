package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectorCodeTest {

    @Test
    fun `canonicalCode minuscule et retire les zeros de tete du numero`() {
        assertEquals("unl-29a-219", canonicalCode("UNL-029a-219"))
        assertEquals("sfd-t3", canonicalCode("sfd-t03"))
        assertEquals("unl-121-219", canonicalCode("unl-121-219"))
    }

    @Test
    fun `canonicalCode laisse le total intact`() {
        assertEquals("ven-sp4-006", canonicalCode("VEN-SP4-006"))
    }

    @Test
    fun `canonicalCode rejette une entree nulle`() {
        assertNull(canonicalCode(null))
    }

    @Test
    fun `canonicalCode garde un suffixe de variante sans zeros de tete`() {
        assertEquals("unl-229*-219", canonicalCode("UNL-229*-219"))
    }

    @Test
    fun `canonicalCode d une chaine vide renvoie la chaine vide`() {
        assertEquals("", canonicalCode(""))
    }

    @Test
    fun `canonicalCode d une chaine sans tiret - test de caracterisation`() {
        // Comportement actuel documente, pas une exigence : sans tiret, le code
        // ne passe jamais par le retrait des zeros de tete, il est seulement mis en minuscules.
        assertEquals("abc123", canonicalCode("abc123"))
    }
}
