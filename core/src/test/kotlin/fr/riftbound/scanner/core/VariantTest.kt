package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun card(name: String, id: String = name) = Card(
    code = "opp-259-298", riftcodexId = id, tcgplayerId = "1", name = name,
    set = "OPP", setLabel = "Promo", number = 259, rarity = "Promo",
    type = "Legend", domain = listOf("Calm")
)

class VariantTest {

    @Test fun `variantOf extrait le suffixe de nom`() {
        assertEquals("Metal", variantOf("Yasuo - Unforgiven (Metal)"))
        assertEquals("Alternate Art", variantOf("Poppy - Paragon (Alternate Art)"))
        assertNull(variantOf("Bewitching Spirit"))
        assertNull(variantOf(null))
    }

    @Test fun `variantOf ignore une parenthese qui n est pas en fin de nom`() {
        assertNull(variantOf("Gold // Buff (jeton) recto"))
    }

    @Test fun `resolveVariant laisse passer une carte unique`() {
        val c = card("Yasuo - Unforgiven")
        assertEquals(Resolution.Unique(c), resolveVariant(listOf(c), "metal"))
    }

    @Test fun `resolveVariant tranche un couple Metal selon la finition`() {
        val normal = card("Yasuo - Unforgiven")
        val metal = card("Yasuo - Unforgiven (Metal)", "metal-id")
        assertEquals(Resolution.Unique(metal), resolveVariant(listOf(normal, metal), "metal"))
        assertEquals(Resolution.Unique(normal), resolveVariant(listOf(normal, metal), "normal"))
    }

    @Test fun `resolveVariant rend la main quand le reglage ne tranche pas`() {
        val a = card("Carte A", "a")
        val b = card("Carte B", "b")
        assertEquals(Resolution.Ambiguous(listOf(a, b)), resolveVariant(listOf(a, b), "normal"))
    }

    @Test fun `resolveVariant refuse une finition hors contrat`() {
        val c = card("Yasuo - Unforgiven")
        assertFailsWith<IllegalArgumentException> { resolveVariant(listOf(c), "foil") }
    }
}
