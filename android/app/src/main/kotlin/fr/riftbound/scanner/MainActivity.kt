package fr.riftbound.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import fr.riftbound.scanner.core.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            coroutineScope.launch(Dispatchers.IO) { store.save(next) }
        }
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
            when (currentTab) {
                AppTab.SCAN -> ScanScreen(
                    entries = entries,
                    onEntriesChange = ::updateEntries,
                    settings = settings,
                    onSettingsChange = ::updateSettings
                )

                AppTab.COLLECTION -> CollectionScreen(
                    entries = entries,
                    onEntriesChange = ::updateEntries,
                    collectionWasCorrupted = collectionWasCorrupted,
                    onCorruptionWarningDismissed = { collectionWasCorrupted = false }
                )
            }
        }
    }
}
