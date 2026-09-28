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
    fun `canonicalCode retire les zeros de tete en presence d un suffixe de variante`() {
        assertEquals("unl-29*-219", canonicalCode("UNL-029*-219"))
    }

    @Test
    fun `canonicalCode garde un suffixe de variante deja sans zeros de tete`() {
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

    @Test
    fun `lit un code propre`() {
        assertEquals("unl-121-219", parseCollectorCode("UNL-121-219"))
    }

    @Test
    fun `accepte les separateurs rencontres a l impression`() {
        for (raw in listOf("UNL 121 219", "UNL/121/219", "UNL·121·219", "UNL – 121 – 219")) {
            assertEquals("unl-121-219", parseCollectorCode(raw), raw)
        }
    }

    @Test
    fun `conserve le suffixe de variante`() {
        assertEquals("unl-116a-219", parseCollectorCode("UNL-116a-219"))
        assertEquals("unl-229*-219", parseCollectorCode("UNL-229*-219"))
    }

    @Test
    fun `corrige les confusions de l OCR`() {
        assertEquals("unl-121-219", parseCollectorCode("UNL-I2I-2I9"))
        assertEquals("unl-29a-219", parseCollectorCode("UNL-O29a-2I9"))
        assertEquals("ogn-12-219", parseCollectorCode("0GN-012-219"))
    }

    @Test
    fun `accepte les codes a prefixe litteral`() {
        assertEquals("sfd-t3", parseCollectorCode("SFD-T03"))
        assertEquals("ven-r6", parseCollectorCode("VEN-R06"))
        assertEquals("ven-sp4-006", parseCollectorCode("VEN-SP4-006"))
    }

    @Test
    fun `ignore le texte qui entoure le code`() {
        assertEquals("unl-121-219", parseCollectorCode("  UNL-121-219   Jonathan Santoro  "))
    }

    @Test
    fun `ne se laisse pas piéger par un texte de regles`() {
        // Bug reel : la premiere correspondance venue donnait "deal-2-2".
        assertEquals("unl-121-219", parseCollectorCode("Deal -2/-2 to a unit. UNL-121-219"))
        assertEquals("unl-121-219", parseCollectorCode("Give -1/-1 until end of turn. UNL-121-219"))
        assertEquals("unl-121-219", parseCollectorCode("ab-12 UNL-121-219"))
        assertNull(parseCollectorCode("Deal -2/-2 to a unit."))
    }

    @Test
    fun `accepte un set inconnu de forme plausible`() {
        // Regle produit : ne jamais perdre un scan. Une extension future doit
        // pouvoir produire une ligne « inconnue » plutot que d etre rejetee.
        assertEquals("rad-12-200", parseCollectorCode("RAD-012-200"))
        assertEquals("zzz-999-999", parseCollectorCode("ZZZ-999-999"))
    }

    @Test
    fun `rejette ce qui n est pas un code`() {
        for (raw in listOf("", "   ", "Bewitching Spirit", "219", "Illustration : Wild Blue Studios")) {
            assertNull(parseCollectorCode(raw), raw)
        }
        assertNull(parseCollectorCode(null))
    }

    @Test
    fun `pickCodeText retient le seul bloc porteur d un code`() {
        assertEquals("UNL-121-219", pickCodeText(listOf("Bewitching Spirit", "UNL-121-219")))
    }

    @Test
    fun `pickCodeText renvoie null si aucun bloc n a de code`() {
        assertNull(pickCodeText(listOf("Bewitching Spirit", "Illustration : Wild Blue Studios")))
    }

    @Test
    fun `pickCodeText d une liste vide renvoie null`() {
        assertNull(pickCodeText(emptyList()))
    }

    @Test
    fun `pickCodeText trouve le code meme place en dernier bloc`() {
        // Cas reel qui echouait : le nom, le texte de regles et l illustrateur
        // arrivent avant le bloc du code de collection.
        assertEquals(
            "UNL-121-219",
            pickCodeText(listOf("Bewitching Spirit", "Deal -2/-2 to a unit.", "Wild Blue Studios", "UNL-121-219"))
        )
    }

    @Test
    fun `pickCodeText n est pas piege par un texte de regles qui ressemble a un code`() {
        assertEquals(
            "UNL-121-219",
            pickCodeText(listOf("Deal -2/-2 to a unit.", "UNL-121-219"))
        )
    }

    @Test
    fun `parsePrintedLanguage lit le code de langue adjacent au code de collection`() {
        for (raw in listOf("UNL · 070/219 · FR", "UNL 070/219 FR", "UNL-070-219-FR")) {
            assertEquals("fr", parsePrintedLanguage(raw), raw)
        }
        assertEquals("en", parsePrintedLanguage("UNL · 070/219 · EN"))
    }

    @Test
    fun `parsePrintedLanguage renvoie null en l absence de code de langue`() {
        assertNull(parsePrintedLanguage("UNL · 070/219"))
    }

    @Test
    fun `parsePrintedLanguage renvoie null pour un jeton de deux lettres qui n est pas une langue connue`() {
        assertNull(parsePrintedLanguage("UNL · 070/219 · XX"))
    }

    @Test
    fun `parsePrintedLanguage renvoie null en l absence de code de collection`() {
        assertNull(parsePrintedLanguage("Bewitching Spirit"))
    }

    @Test
    fun `parsePrintedLanguage rejette une entree nulle ou vide`() {
        assertNull(parsePrintedLanguage(null))
        assertNull(parsePrintedLanguage(""))
    }

    @Test
    fun `parseCollectorCode n est pas perturbe par le code de langue qui suit`() {
        // Non-regression : la ligne complete porte desormais aussi la langue,
        // la lecture du code de collection doit continuer d ignorer ce jeton.
        assertEquals("unl-70-219", parseCollectorCode("UNL · 070/219 · FR"))
    }
}
