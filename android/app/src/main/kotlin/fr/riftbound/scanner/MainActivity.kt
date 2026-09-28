package fr.riftbound.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.riftbound.scanner.core.Card
import fr.riftbound.scanner.core.CommitResult
import fr.riftbound.scanner.core.Entry
import fr.riftbound.scanner.core.commitCard
import fr.riftbound.scanner.core.incrementEntry
import fr.riftbound.scanner.core.removeEntry
import fr.riftbound.scanner.core.updateEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    RiftboundApp()
                }
            }
        }
    }
}

/** Les deux ecrans de l'application, navigables depuis la barre du bas. */
private enum class AppTab(val label: String) {
    SCAN("Scanner"),
    COLLECTION("Collection")
}

/**
 * Racine de l'application : possede la collection et les reglages de session,
 * les charge une fois au demarrage et les persiste a chaque changement, pour
 * que l'ecran de scan et l'ecran de collection partagent toujours le meme
 * etat, meme apres une fermeture complete de l'application.
 */
@Composable
private fun RiftboundApp() {
    val context = LocalContext.current
    val store = remember { CollectionStore(context) }
    val coroutineScope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(AppTab.SCAN) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var settings by remember { mutableStateOf(SessionSettings()) }
    var collectionWasCorrupted by remember { mutableStateOf(false) }
    var isLoaded by remember { mutableStateOf(false) }
    // Vrai des que la derniere sauvegarde a echoue (ecriture ou renommage) :
    // reste vrai tant qu'aucune sauvegarde suivante n'a reussi, pour que
    // l'utilisateur sache que ce qu'il voit a l'ecran n'est peut-etre plus
    // persiste - la regle du produit est de ne jamais perdre un scan en
    // silence.
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loadedEntries = withContext(Dispatchers.IO) { store.load() }
        collectionWasCorrupted = store.lastLoadWasCorrupted
        entries = loadedEntries
        settings = withContext(Dispatchers.IO) { store.loadSettings() }
        isLoaded = true
    }

    fun updateEntries(next: List<Entry>) {
        entries = next
        if (isLoaded) {
            // Rattrapee ici, jamais laissee remonter : `coroutineScope` n'est
            // pas supervise, une exception non rattrapee dans une coroutine
            // qu'il lance annule tout son Job et empecherait silencieusement
            // toute sauvegarde ulterieure pour le reste de la session, alors
            // que l'ecran continuerait d'afficher une collection qui n'est
            // plus persistee.
            coroutineScope.launch {
                try {
                    store.save(next)
                    saveFailed = false
                } catch (e: IOException) {
                    saveFailed = true
                }
            }
        }
    }

    // Point unique qui fait entrer une carte scannee dans la collection.
    // `entries`, ici, est l'etat de snapshot detenu par ce composable (via
    // `remember { mutableStateOf(...) }`) : le lire a l'interieur de cette
    // fonction rend toujours la valeur courante, jamais une copie capturee
    // au moment ou une camera ou un dialogue plus bas dans l'arbre a
    // memorise une reference vers cette fonction. C'est ce qui protege
    // structurellement contre le bug ou l'ecran de scan gardait une liste
    // figee au demarrage : la source de verite n'est jamais transportee,
    // seule la decision (carte, code, reglages) l'est.
    fun commitScan(
        card: Card?, rawCode: String, finish: String, language: String, condition: String
    ): CommitResult {
        val result = commitCard(entries, card, rawCode, finish, language, condition, Instant.now().toString())
        val nextEntries = when (result) {
            is CommitResult.Added -> result.entries
            is CommitResult.Incremented -> result.entries
        }
        updateEntries(nextEntries)
        return result
    }

    // Meme raisonnement pour les corrections faites depuis l'ecran
    // Collection (incrementation manuelle, changement de langue ou d'etat,
    // suppression) : chacune applique sa transformation sur la collection
    // courante plutot que de recevoir une liste et d'en renvoyer une
    // nouvelle calculee a partir d'une valeur qui aurait pu etre capturee
    // plus tot.
    fun incrementCollectionEntry(index: Int) {
        updateEntries(incrementEntry(entries, index))
    }

    fun changeEntryLanguage(index: Int, value: String) {
        updateEntries(updateEntry(entries, index, language = value))
    }

    fun changeEntryCondition(index: Int, value: String) {
        updateEntries(updateEntry(entries, index, condition = value))
    }

    fun removeCollectionEntry(index: Int) {
        updateEntries(removeEntry(entries, index))
    }

    fun updateSettings(next: SessionSettings) {
        settings = next
        if (isLoaded) {
            coroutineScope.launch(Dispatchers.IO) {
                store.saveSettings(next.finish, next.language, next.condition)
            }
        }
    }

    // Le chargement initial n'a pas encore de collection a afficher : mieux
    // vaut ne rien montrer un instant que de montrer puis vider la collection.
    if (!isLoaded) return

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {},
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(modifier = Modifier.padding(padding)) {
            Column {
                if (saveFailed) {
                    Text(
                        text = "Echec de l'enregistrement de la collection : les derniers " +
                            "changements ne sont peut-etre pas sauvegardes.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                // `weight(1f)` : sans lui, un enfant `fillMaxSize()` de cette
                // Column reclame la hauteur totale disponible sans tenir
                // compte de la banniere au-dessus, et deborde en bas de
                // l'ecran des que `saveFailed` est vrai.
                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        AppTab.SCAN -> ScanScreen(
                            entries = entries,
                            onCommit = ::commitScan,
                            settings = settings,
                            onSettingsChange = ::updateSettings
                        )

                        AppTab.COLLECTION -> CollectionScreen(
                            entries = entries,
                            onIncrement = ::incrementCollectionEntry,
                            onLanguageChange = ::changeEntryLanguage,
                            onConditionChange = ::changeEntryCondition,
                            onRemove = ::removeCollectionEntry,
                            collectionWasCorrupted = collectionWasCorrupted,
                            onCorruptionWarningDismissed = { collectionWasCorrupted = false }
                        )
                    }
                }
            }
        }
    }
}
