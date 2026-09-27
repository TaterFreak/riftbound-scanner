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
}
