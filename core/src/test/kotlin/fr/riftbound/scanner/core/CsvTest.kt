package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val row = Entry(
    code = "unl-121-219", name = "Bewitching Spirit", set = "UNL", setLabel = "Unleashed",
    number = 121, rarity = "Common", type = "Unit", domain = listOf("Chaos"),
    riftcodexId = "abc", tcgplayerId = "685592", variant = null, finish = "normal",
    language = "en", condition = "NM", quantity = 2, rawCode = "unl-121-219",
    scannedAt = "2026-09-27T10:00:00Z", unknown = false
)

private fun rows(csv: String) = csv.removePrefix("﻿").trimEnd().split("\r\n")

class CsvTest {

    @Test fun `commence par un BOM et un en-tete de dix-huit colonnes`() {
        val csv = toCsv(emptyList())
        assertTrue(csv.startsWith("﻿"))
        assertEquals(18, rows(csv)[0].split(";").size)
        assertTrue(rows(csv)[0].startsWith("quantity;name;set;"))
    }

    @Test fun `ecrit une ligne complete`() {
        val cols = rows(toCsv(listOf(row)))[1].split(";")
        assertEquals("2", cols[0])
        assertEquals("Bewitching Spirit", cols[1])
        assertEquals("unl-121-219", cols[5])
    }

    @Test fun `joint les domaines par une barre verticale`() {
        val e = row.copy(domain = listOf("Fury", "Order"))
        val header = rows(toCsv(listOf(e)))[0].split(";")
        val cols = rows(toCsv(listOf(e)))[1].split(";")
        assertEquals("Fury|Order", cols[header.indexOf("domain")])
    }

    @Test fun `laisse la colonne price vide`() {
        val header = rows(toCsv(listOf(row)))[0].split(";")
        val cols = rows(toCsv(listOf(row)))[1].split(";")
        assertEquals("", cols[header.indexOf("price")])
    }

    @Test fun `protege les caracteres speciaux`() {
        assertTrue(rows(toCsv(listOf(row.copy(name = "Gold; Buff"))))[1].contains("\"Gold; Buff\""))
        assertTrue(rows(toCsv(listOf(row.copy(name = "Le \"Boss\""))))[1].contains("\"Le \"\"Boss\"\"\""))
    }

    @Test fun `neutralise une injection de formule`() {
        val cols = rows(toCsv(listOf(row.copy(name = "=CMD|calc!A1"))))[1]
        assertTrue(cols.contains("'=CMD"))
    }

    @Test fun `exporte une carte inconnue avec son code brut`() {
        val e = row.copy(unknown = true, name = "", code = "zzz-9-9", rawCode = "zzz-9-9",
                          riftcodexId = null, tcgplayerId = null)
        val header = rows(toCsv(listOf(e)))[0].split(";")
        val cols = rows(toCsv(listOf(e)))[1].split(";")
        assertEquals("zzz-9-9", cols[header.indexOf("raw_code")])
    }

    @Test fun `utilise des fins de ligne CRLF`() {
        assertTrue(toCsv(listOf(row)).contains("\r\n"))
    }
}
