package fr.riftbound.scanner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.riftbound.scanner.core.Entry
import fr.riftbound.scanner.core.incrementEntry
import fr.riftbound.scanner.core.removeEntry
import fr.riftbound.scanner.core.toCsv
import fr.riftbound.scanner.core.updateEntry
import java.io.IOException
import java.time.LocalDate

/** Meme liste que la version web, dans le meme ordre : de neuf a hors d'usage. */
private val CONDITIONS = listOf("NM", "EX", "GD", "LP", "PL", "PO")
private val LANGUAGES = listOf("en", "fr")

/** Taille minimale d'une cible tactile (recommandation Material) : 44 points. */
private val MIN_TOUCH_TARGET = 44.dp

@Composable
fun CollectionScreen(
    entries: List<Entry>,
    onEntriesChange: (List<Entry>) -> Unit,
    collectionWasCorrupted: Boolean,
    onCorruptionWarningDismissed: () -> Unit
) {
    val context = LocalContext.current
    var exportFailed by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(toCsv(entries).toByteArray(Charsets.UTF_8))
            }
            exportFailed = false
        } catch (e: IOException) {
            exportFailed = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (collectionWasCorrupted) {
            CorruptionWarning(onDismiss = onCorruptionWarningDismissed)
        }
        if (exportFailed) {
            Text(
                text = "Echec de l'export du CSV, reessayez.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val cardCount = entries.sumOf { it.quantity }
            Text(
                text = "${entries.size} lignes, $cardCount cartes",
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                enabled = entries.isNotEmpty(),
                onClick = { exportLauncher.launch("riftbound-${LocalDate.now()}.csv") }
            ) {
                Text("Exporter en CSV")
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune carte scannee pour le moment.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    CollectionRow(
                        entry = entry,
                        onIncrement = { onEntriesChange(incrementEntry(entries, index)) },
                        onLanguageChange = { value ->
                            onEntriesChange(updateEntry(entries, index, language = value))
                        },
                        onConditionChange = { value ->
                            onEntriesChange(updateEntry(entries, index, condition = value))
                        },
                        onRemove = { onEntriesChange(removeEntry(entries, index)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CorruptionWarning(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "La collection precedente etait illisible et a ete mise de cote. " +
                "Elle repart vide.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/**
 * Carte empilee pour une ligne de collection, plutot qu'une ligne de tableau :
 * sur un ecran de telephone en portrait, un tableau a de nombreuses colonnes
 * deborde toujours de la largeur ecran et fait defiler toute la page
 * lateralement. Ici chaque controle passe a la ligne suivante.
 */
@Composable
private fun CollectionRow(
    entry: Entry,
    onIncrement: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onConditionChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = if (entry.unknown) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (entry.unknown) "Inconnue (${entry.rawCode})" else entry.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val details = listOfNotNull(
                        entry.set.takeIf { it.isNotBlank() },
                        entry.code.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(text = details, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    text = if (entry.finish == "metal") "Metal" else "Normale",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuantityControl(quantity = entry.quantity, onIncrement = onIncrement)
                SelectorButton(
                    label = entry.language.uppercase(),
                    options = LANGUAGES,
                    optionLabel = { it.uppercase() },
                    onSelect = onLanguageChange
                )
                SelectorButton(
                    label = entry.condition,
                    options = CONDITIONS,
                    optionLabel = { it },
                    onSelect = onConditionChange
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET)
                ) {
                    Text("Supprimer")
                }
            }
        }
    }
}

@Composable
private fun QuantityControl(quantity: Int, onIncrement: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "×$quantity", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = onIncrement,
            modifier = Modifier.defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET)
        ) {
            Text("+")
        }
    }
}

@Composable
private fun SelectorButton(
    label: String,
    options: List<String>,
    optionLabel: (String) -> String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET)
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}
