package fr.riftbound.scanner

import android.content.Context
import fr.riftbound.scanner.core.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val COLLECTION_FILE_NAME = "collection.json"
private const val COLLECTION_TMP_FILE_NAME = "collection.json.tmp"
private const val COLLECTION_CORRUPTED_FILE_NAME = "collection.corrompue.json"

private const val PREFS_NAME = "reglages"
private const val PREF_FINISH = "finish"
private const val PREF_LANGUAGE = "language"
private const val PREF_CONDITION = "condition"

private const val DEFAULT_FINISH = "normal"
private const val DEFAULT_LANGUAGE = "en"
private const val DEFAULT_CONDITION = "NM"

/**
 * Reglages de session reportes d'un lancement a l'autre : finition, langue et
 * etat par defaut appliques a une nouvelle carte scannee.
 */
data class SessionSettings(
    val finish: String = DEFAULT_FINISH,
    val language: String = DEFAULT_LANGUAGE,
    val condition: String = DEFAULT_CONDITION
)

/**
 * Persiste la collection en JSON dans le stockage prive de l'application.
 *
 * L'ecriture est atomique : le contenu est d'abord ecrit dans un fichier
 * temporaire, puis ce fichier est renomme a la place du fichier final. Une
 * coupure en cours d'ecriture laisse donc l'ancien fichier intact, jamais un
 * fichier a moitie ecrit.
 *
 * A la lecture, un fichier illisible (JSON corrompu, coupure de courant en
 * plein renommage, etc.) ne doit jamais empecher l'application de demarrer :
 * il est ecarte sous un autre nom et la collection repart vide.
 */
class CollectionStore(private val context: Context) {

    /** Vrai si le dernier appel a [load] a du ecarter un fichier illisible. */
    var lastLoadWasCorrupted: Boolean = false
        private set

    fun load(): List<Entry> {
        lastLoadWasCorrupted = false
        val file = File(context.filesDir, COLLECTION_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            parseEntries(file.readText())
        } catch (e: Exception) {
            quarantineCorruptedFile(file)
            lastLoadWasCorrupted = true
            emptyList()
        }
    }

    fun save(entries: List<Entry>) {
        val file = File(context.filesDir, COLLECTION_FILE_NAME)
        val tmp = File(context.filesDir, COLLECTION_TMP_FILE_NAME)
        tmp.writeText(serializeEntries(entries))
        if (!tmp.renameTo(file)) {
            // Sur certains systemes de fichiers, renameTo echoue si la cible
            // existe deja : on la retire puis on retente une seule fois.
            file.delete()
            if (!tmp.renameTo(file)) {
                throw IOException("Echec du renommage atomique de la collection")
            }
        }
    }

    fun loadSettings(): SessionSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SessionSettings(
            finish = prefs.getString(PREF_FINISH, DEFAULT_FINISH) ?: DEFAULT_FINISH,
            language = prefs.getString(PREF_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE,
            condition = prefs.getString(PREF_CONDITION, DEFAULT_CONDITION) ?: DEFAULT_CONDITION
        )
    }

    fun saveSettings(finish: String, language: String, condition: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_FINISH, finish)
            .putString(PREF_LANGUAGE, language)
            .putString(PREF_CONDITION, condition)
            .apply()
    }

    private fun quarantineCorruptedFile(file: File) {
        val quarantined = File(context.filesDir, COLLECTION_CORRUPTED_FILE_NAME)
        if (quarantined.exists()) quarantined.delete()
        file.renameTo(quarantined)
    }
}

private fun serializeEntries(entries: List<Entry>): String {
    val array = JSONArray()
    entries.forEach { array.put(entryToJson(it)) }
    return array.toString()
}

private fun parseEntries(text: String): List<Entry> {
    val array = JSONArray(text)
    return (0 until array.length()).map { entryFromJson(array.getJSONObject(it)) }
}

private fun entryToJson(entry: Entry): JSONObject = JSONObject().apply {
    put("code", entry.code)
    put("name", entry.name)
    put("set", entry.set)
    put("setLabel", entry.setLabel)
    put("number", entry.number ?: JSONObject.NULL)
    put("rarity", entry.rarity)
    put("type", entry.type)
    put("domain", JSONArray(entry.domain))
    put("riftcodexId", entry.riftcodexId ?: JSONObject.NULL)
    put("tcgplayerId", entry.tcgplayerId ?: JSONObject.NULL)
    put("variant", entry.variant ?: JSONObject.NULL)
    put("finish", entry.finish)
    put("language", entry.language)
    put("condition", entry.condition)
    put("quantity", entry.quantity)
    put("rawCode", entry.rawCode)
    put("scannedAt", entry.scannedAt)
    put("unknown", entry.unknown)
}

private fun entryFromJson(json: JSONObject): Entry {
    val domain = json.getJSONArray("domain")
    return Entry(
        code = json.getString("code"),
        name = json.getString("name"),
        set = json.getString("set"),
        setLabel = json.getString("setLabel"),
        number = if (json.isNull("number")) null else json.getInt("number"),
        rarity = json.getString("rarity"),
        type = json.getString("type"),
        domain = (0 until domain.length()).map { domain.getString(it) },
        riftcodexId = if (json.isNull("riftcodexId")) null else json.getString("riftcodexId"),
        tcgplayerId = if (json.isNull("tcgplayerId")) null else json.getString("tcgplayerId"),
        variant = if (json.isNull("variant")) null else json.getString("variant"),
        finish = json.getString("finish"),
        language = json.getString("language"),
        condition = json.getString("condition"),
        quantity = json.getInt("quantity"),
        rawCode = json.getString("rawCode"),
        scannedAt = json.getString("scannedAt"),
        unknown = json.getBoolean("unknown")
    )
}
