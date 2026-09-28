package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val bewitching = Card(
    code = "unl-121-219", riftcodexId = "abc", tcgplayerId = "685592",
    name = "Bewitching Spirit", set = "UNL", setLabel = "Unleashed", number = 121,
    rarity = "Common", type = "Unit", domain = listOf("Chaos")
)

class EntriesTest {

    private fun scan(entries: List<Entry>, card: Card?, raw: String = "unl-121-219",
                     finish: String = "normal", language: String = "en", condition: String = "NM") =
        addScan(entries, card, raw, finish, language, condition, "2026-09-27T10:00:00Z")

    @Test fun `addScan cree une entree complete`() {
        val (entries, outcome) = scan(emptyList(), bewitching)
        assertEquals(ScanOutcome.Added(0), outcome)
        assertEquals(1, entries.size)
        assertEquals("Bewitching Spirit", entries[0].name)
        assertEquals(1, entries[0].quantity)
        assertEquals(false, entries[0].unknown)
    }

    @Test fun `addScan n incremente pas de lui-meme un doublon`() {
        val firstEntries = scan(emptyList(), bewitching).first
        val (entries, outcome) = scan(firstEntries, bewitching)
        assertEquals(ScanOutcome.Duplicate(0), outcome)
        assertEquals(1, entries[0].quantity)
        assertEquals(firstEntries, entries)
    }

    @Test fun `addScan distingue deux cartes differentes de meme code`() {
        // Bug reel : la version Metal disparaissait, absorbee par la ligne normale.
        val metal = bewitching.copy(riftcodexId = "metal-id", name = "Bewitching Spirit (Metal)")
        val afterFirst = scan(emptyList(), bewitching).first
        val (entries, outcome) = scan(afterFirst, metal)
        assertEquals(ScanOutcome.Added(1), outcome)
        assertEquals(2, entries.size)
    }

    @Test fun `addScan distingue les finitions langues et etats`() {
        var entries = scan(emptyList(), bewitching).first
        entries = scan(entries, bewitching, finish = "metal").first
        entries = scan(entries, bewitching, language = "fr").first
        entries = scan(entries, bewitching, condition = "EX").first
        assertEquals(4, entries.size)
    }

    @Test fun `addScan enregistre une carte inconnue sans la perdre`() {
        val (entries, outcome) = scan(emptyList(), null, raw = "zzz-999-999")
        assertTrue(outcome is ScanOutcome.Added)
        assertEquals(true, entries[0].unknown)
        assertEquals("zzz-999-999", entries[0].rawCode)
        assertEquals("", entries[0].name)
    }

    @Test fun `deux cartes inconnues de codes differents restent distinctes`() {
        val entries = scan(emptyList(), null, raw = "zzz-1-1").first
        val (after, outcome) = scan(entries, null, raw = "zzz-2-2")
        assertTrue(outcome is ScanOutcome.Added)
        assertEquals(2, after.size)
        assertNotEquals(entryKey(after[0]), entryKey(after[1]))
    }

    @Test fun `le domaine de la ligne reprend celui de la carte`() {
        // En JavaScript, le tableau `domain` etait partage par reference avec le
        // catalogue et le muter le corrompait. En Kotlin, `List` est en lecture
        // seule : le bug ne peut pas se reproduire. La copie defensive dans
        // `entryFromScan` reste par prudence, et ce test verifie le contenu.
        val (entries, _) = scan(emptyList(), bewitching)
        assertEquals(listOf("Chaos"), entries[0].domain)
    }

    @Test fun `incrementEntry ajoute un exemplaire sans muter`() {
        val before = scan(emptyList(), bewitching).first
        val after = incrementEntry(before, 0)
        assertEquals(2, after[0].quantity)
        assertEquals(1, before[0].quantity)
    }

    @Test fun `updateEntry et removeEntry sont immuables`() {
        val before = scan(emptyList(), bewitching).first
        assertEquals("EX", updateEntry(before, 0, condition = "EX")[0].condition)
        assertEquals("NM", before[0].condition)
        assertEquals(emptyList(), removeEntry(before, 0))
    }

    // commitCard : point unique de decision pour toute carte choisie ou
    // reconnue (scan direct, confirmation de doublon, choix de variante...).
    // Elle rend l'incrementation d'un doublon impossible a oublier : c'est
    // elle qui l'effectue, l'appelant n'a plus qu'a lire le resultat.
    private fun commit(entries: List<Entry>, card: Card?, raw: String = "unl-121-219",
                        finish: String = "normal", language: String = "en", condition: String = "NM") =
        commitCard(entries, card, raw, finish, language, condition, "2026-09-27T10:00:00Z")

    @Test fun `commitCard ajoute une carte inedite`() {
        val result = commit(emptyList(), bewitching)
        assertTrue(result is CommitResult.Added)
        result as CommitResult.Added
        assertEquals(0, result.index)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.entries[0].quantity)
        assertEquals("Bewitching Spirit", result.entries[0].name)
    }

    @Test fun `commitCard incremente reellement une carte deja presente`() {
        // C'est le cas qui, dans ScanScreen, ne produisait avant correction
        // aucun changement du tout : un candidat choisi qui correspondait a
        // une ligne existante ne l'incrementait pas. commitCard verrouille
        // ce comportement une bonne fois pour toutes.
        val firstEntries = (commit(emptyList(), bewitching) as CommitResult.Added).entries
        val result = commit(firstEntries, bewitching)
        assertTrue(result is CommitResult.Incremented)
        result as CommitResult.Incremented
        assertEquals(2, result.quantity)
        assertEquals(2, result.entries[result.index].quantity)
        assertNotEquals(firstEntries, result.entries)
    }

    @Test fun `commitCard conserve une carte inconnue sous son code brut`() {
        val result = commit(emptyList(), null, raw = "zzz-999-999")
        assertTrue(result is CommitResult.Added)
        result as CommitResult.Added
        assertEquals("zzz-999-999", result.entries[result.index].rawCode)
        assertEquals(true, result.entries[result.index].unknown)
    }

    @Test fun `commitCard distingue deux cartes differentes de meme code et meme finition`() {
        // Bug reel deja couvert pour addScan : la version Metal ne doit pas
        // etre absorbee par la ligne normale. Verifie ici a travers
        // commitCard, le seul point d'entree desormais utilise par l'ecran.
        val metal = bewitching.copy(riftcodexId = "metal-id", name = "Bewitching Spirit (Metal)")
        val firstEntries = (commit(emptyList(), bewitching) as CommitResult.Added).entries
        val result = commit(firstEntries, metal)
        assertTrue(result is CommitResult.Added)
        result as CommitResult.Added
        assertEquals(2, result.entries.size)
        assertEquals(1, result.entries[0].quantity)
        assertEquals(1, result.entries[1].quantity)
    }
}
