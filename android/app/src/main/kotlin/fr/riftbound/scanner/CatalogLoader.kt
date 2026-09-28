package fr.riftbound.scanner

import android.content.Context
import fr.riftbound.scanner.core.Card
import fr.riftbound.scanner.core.Catalog
import org.json.JSONObject

/** Lit le catalogue embarque. A appeler une fois, hors du fil principal. */
fun loadCatalog(context: Context): Catalog {
    val text = context.assets.open("cards.json").bufferedReader().use { it.readText() }
    val array = JSONObject(text).getJSONArray("cards")
    val cards = (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        val domains = item.getJSONArray("domain")
        Card(
            code = item.getString("code"),
            riftcodexId = item.getString("riftcodexId"),
            tcgplayerId = if (item.isNull("tcgplayerId")) null else item.getString("tcgplayerId"),
            name = item.getString("name"),
            set = item.getString("set"),
            setLabel = item.getString("setLabel"),
            number = if (item.isNull("number")) null else item.getInt("number"),
            rarity = item.getString("rarity"),
            type = item.getString("type"),
            domain = (0 until domains.length()).map { domains.getString(it) }
        )
    }
    return Catalog(cards)
}
