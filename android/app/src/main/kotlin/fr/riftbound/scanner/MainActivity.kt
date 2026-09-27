package fr.riftbound.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import fr.riftbound.scanner.core.canonicalCode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    // Preuve que la bibliotheque core est bien liee a l'application.
                    Text("Riftbound — core repond : " + canonicalCode("UNL-029a-219"))
                }
            }
        }
    }
}
