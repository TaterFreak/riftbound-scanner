package fr.riftbound.scanner.core

// Point-virgule et BOM : Excel en configuration francaise ouvre alors le fichier
// correctement d'un double-clic, sans cesser d'etre du CSV standard.

private val COLUMNS: List<Pair<String, (Entry) -> Any?>> = listOf(
    "quantity" to { e: Entry -> e.quantity },
    "name" to { e: Entry -> e.name },
    "set" to { e: Entry -> e.set },
    "set_label" to { e: Entry -> e.setLabel },
    "collector_number" to { e: Entry -> e.number ?: "" },
    "riftbound_id" to { e: Entry -> e.code },
    "variant" to { e: Entry -> e.variant ?: "" },
    "rarity" to { e: Entry -> e.rarity },
    "type" to { e: Entry -> e.type },
    "domain" to { e: Entry -> e.domain.joinToString("|") },
    "finish" to { e: Entry -> e.finish },
    "language" to { e: Entry -> e.language },
    "condition" to { e: Entry -> e.condition },
    "price" to { _: Entry -> "" },
    "riftcodex_id" to { e: Entry -> e.riftcodexId ?: "" },
    "tcgplayer_id" to { e: Entry -> e.tcgplayerId ?: "" },
    "raw_code" to { e: Entry -> e.rawCode },
    "scanned_at" to { e: Entry -> e.scannedAt }
)

private val NEEDS_QUOTING = Regex("[\";\r\n]")
private val FORMULA_PREFIX = Regex("^[=+\\-@]")

private fun escape(value: Any?): String {
    var text = value?.toString() ?: ""
    // Neutralise l'interpretation en formule a l'ouverture dans un tableur.
    if (FORMULA_PREFIX.containsMatchIn(text)) text = "'$text"
    return if (NEEDS_QUOTING.containsMatchIn(text)) "\"" + text.replace("\"", "\"\"") + "\"" else text
}

fun toCsv(entries: List<Entry>): String {
    val header = COLUMNS.joinToString(";") { it.first }
    val rows = entries.map { e -> COLUMNS.joinToString(";") { escape(it.second(e)) } }
    return "﻿" + (listOf(header) + rows).joinToString("\r\n") + "\r\n"
}
