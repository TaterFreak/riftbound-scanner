package fr.riftbound.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.riftbound.scanner.core.Card
import fr.riftbound.scanner.core.Catalog
import fr.riftbound.scanner.core.Entry
import fr.riftbound.scanner.core.ScanEvent
import fr.riftbound.scanner.core.ScanMachine
import fr.riftbound.scanner.core.addScan
import fr.riftbound.scanner.core.incrementEntry
import fr.riftbound.scanner.core.pickCodeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ecran de scan. La collection et les reglages de session (finition, langue,
 * etat par defaut) vivent au niveau de l'application : cet ecran les recoit
 * en parametre et notifie ses changements, pour que la collection scannee ici
 * soit aussi celle affichee et persistee depuis l'ecran Collection.
 */
@Composable
fun ScanScreen(
    entries: List<Entry>,
    onEntriesChange: (List<Entry>) -> Unit,
    settings: SessionSettings,
    onSettingsChange: (SessionSettings) -> Unit
) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<Catalog?>(null) }

    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { loadCatalog(context) }
    }

    val loadedCatalog = catalog
    if (loadedCatalog == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    ScanScreenContent(
        catalog = loadedCatalog,
        entries = entries,
        onEntriesChange = onEntriesChange,
        settings = settings,
        onSettingsChange = onSettingsChange
    )
}

@Composable
private fun ScanScreenContent(
    catalog: Catalog,
    entries: List<Entry>,
    onEntriesChange: (List<Entry>) -> Unit,
    settings: SessionSettings,
    onSettingsChange: (SessionSettings) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    // ComponentActivity implemente LifecycleOwner : c'est le cycle de vie auquel
    // la camera doit etre liee pour se couper proprement en arriere-plan.
    val lifecycleOwner = context as LifecycleOwner
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val scanMachine = remember(catalog) { ScanMachine(catalog) }

    // Etat par defaut applique a une nouvelle carte scannee : pas de selecteur
    // dedie sur cet ecran, ajustable plus tard depuis la collection.
    val condition = settings.condition

    var finish by remember { mutableStateOf(settings.finish) }
    var language by remember { mutableStateOf(settings.language) }

    fun setFinish(value: String) {
        finish = value
        onSettingsChange(settings.copy(finish = value))
    }

    fun setLanguage(value: String) {
        language = value
        onSettingsChange(settings.copy(language = value))
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionWasDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionWasDenied = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var pendingEvent by remember { mutableStateOf<ScanEvent?>(null) }
    var manualCode by remember { mutableStateOf("") }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var analysisExecutorRef by remember { mutableStateOf<ExecutorService?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef?.unbindAll()
            analysisExecutorRef?.shutdown()
        }
    }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) {
        onDispose { toneGenerator.release() }
    }

    fun confirmAdditionFeedback() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    // Confirmation textuelle d'un ajout reussi : vibration et bip restent muets
    // sur le nom de la carte, ce message comble ce manque sans jamais bloquer
    // le scan. Un nouveau message coupe l'ancien plutot que de faire la queue,
    // pour qu'une pile de cartes scannees coup sur coup reste lisible.
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun showAdditionMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    fun handleEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.Accept -> {
                val (nextEntries, _) = addScan(
                    entries, event.card, event.code, finish, language, condition,
                    Instant.now().toString()
                )
                onEntriesChange(nextEntries)
                confirmAdditionFeedback()
                showAdditionMessage("${event.card.name} ajoutée")
            }

            is ScanEvent.Duplicate, is ScanEvent.Ambiguous, is ScanEvent.Unknown -> {
                pendingEvent = event
            }
        }
    }

    fun submitManualCode() {
        val code = manualCode.trim()
        if (code.isEmpty()) return
        handleEvent(scanMachine.decide(code, finish, language, condition, entries))
        manualCode = ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTapGestures { offset ->
                            val activePreview = previewView ?: return@detectTapGestures
                            val activeCamera = camera ?: return@detectTapGestures
                            val point = activePreview.meteringPointFactory.createPoint(offset.x, offset.y)
                            activeCamera.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point).build()
                            )
                        }
                    },
                factory = { viewContext ->
                    val newPreviewView = PreviewView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    previewView = newPreviewView

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProviderRef = cameraProvider

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(newPreviewView.surfaceProvider)
                        }

                        val analysisExecutor = Executors.newSingleThreadExecutor()
                        analysisExecutorRef = analysisExecutor

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(
                                    analysisExecutor,
                                    CardAnalyzer(
                                        machine = scanMachine,
                                        currentState = {
                                            ScanUiState(
                                                finish = finish,
                                                language = language,
                                                condition = condition,
                                                entries = entries,
                                                paused = pendingEvent != null
                                            )
                                        },
                                        onEvent = { event -> mainExecutor.execute { handleEvent(event) } }
                                    )
                                )
                            }

                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    }, mainExecutor)

                    newPreviewView
                }
            )
        } else {
            PermissionRefusee(
                wasDenied = permissionWasDenied,
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }

        // Reglages collants : finition et langue, plus le compteur et la torche.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FinishToggle(finish = finish, onFinishChange = { setFinish(it) })
            LanguageToggle(language = language, onLanguageChange = { setLanguage(it) })
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "${entries.sumOf { it.quantity }} carte(s)", color = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                enabled = camera != null,
                onClick = {
                    val next = !torchOn
                    camera?.cameraControl?.enableTorch(next)
                    torchOn = next
                }
            ) {
                Text(if (torchOn) "Torche : marche" else "Torche : arret", color = Color.White)
            }
        }

        // Saisie manuelle, toujours accessible meme sans permission camera.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text("Code de collection") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { submitManualCode() }) { Text("Ajouter") }
        }

        // Place au-dessus de la saisie manuelle et sous les reglages, pour ne
        // recouvrir ni l'un ni l'autre.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }

    pendingEvent?.let { event ->
        DecisionDialog(
            event = event,
            onDismiss = {
                pendingEvent = null
                scanMachine.reset()
            },
            onConfirmDuplicate = { index ->
                val nextEntries = incrementEntry(entries, index)
                onEntriesChange(nextEntries)
                confirmAdditionFeedback()
                val card = entries[index]
                showAdditionMessage("${card.name} — quantité portée à ${nextEntries[index].quantity}")
                pendingEvent = null
                scanMachine.reset()
            },
            onChooseCard = { code, card ->
                val (nextEntries, _) = addScan(
                    entries, card, code, finish, language, condition, Instant.now().toString()
                )
                onEntriesChange(nextEntries)
                confirmAdditionFeedback()
                pendingEvent = null
                scanMachine.reset()
            },
            onKeepUnknown = { code ->
                val (nextEntries, _) = addScan(
                    entries, null, code, finish, language, condition, Instant.now().toString()
                )
                onEntriesChange(nextEntries)
                confirmAdditionFeedback()
                showAdditionMessage("Code $code conservé comme carte inconnue")
                pendingEvent = null
                scanMachine.reset()
            }
        )
    }
}

@Composable
private fun FinishToggle(finish: String, onFinishChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = finish == "normal", onClick = { onFinishChange("normal") }, label = { Text("Normale") })
        FilterChip(selected = finish == "metal", onClick = { onFinishChange("metal") }, label = { Text("Metal") })
    }
}

@Composable
private fun LanguageToggle(language: String, onLanguageChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = language == "en", onClick = { onLanguageChange("en") }, label = { Text("EN") })
        FilterChip(selected = language == "fr", onClick = { onLanguageChange("fr") }, label = { Text("FR") })
    }
}

@Composable
private fun PermissionRefusee(wasDenied: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                text = "L'appareil photo est necessaire pour scanner les cartes.",
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (wasDenied) {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Ouvrir les reglages de l'application")
                }
            } else {
                Button(onClick = onRetry) {
                    Text("Autoriser l'appareil photo")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "La saisie manuelle du code reste disponible en bas de l'ecran.",
                color = Color.White
            )
        }
    }
}

@Composable
private fun DecisionDialog(
    event: ScanEvent,
    onDismiss: () -> Unit,
    onConfirmDuplicate: (Int) -> Unit,
    onChooseCard: (String, Card) -> Unit,
    onKeepUnknown: (String) -> Unit
) {
    when (event) {
        // Jamais affiche : ScanEvent.Accept est traite immediatement, sans dialogue.
        is ScanEvent.Accept -> Unit

        is ScanEvent.Duplicate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Doublon") },
            text = { Text("« ${event.card.name} » est deja dans la collection. Incrementer la quantite ?") },
            confirmButton = {
                TextButton(onClick = { onConfirmDuplicate(event.index) }) { Text("Incrementer") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Ignorer") }
            }
        )

        is ScanEvent.Ambiguous -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Variante ambigue") },
            text = {
                Column {
                    Text("Code « ${event.code} » : plusieurs cartes correspondent, la finition ne suffit pas a trancher.")
                    Spacer(modifier = Modifier.height(8.dp))
                    event.cards.forEach { card ->
                        TextButton(onClick = { onChooseCard(event.code, card) }) {
                            Text("${card.name} — ${card.code}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        )

        is ScanEvent.Unknown -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Code inconnu") },
            text = {
                Column {
                    Text("Code « ${event.code} » introuvable dans le catalogue.")
                    if (event.candidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Cartes proches :")
                        event.candidates.forEach { card ->
                            TextButton(onClick = { onChooseCard(event.code, card) }) {
                                Text("${card.name} — ${card.code}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onKeepUnknown(event.code) }) { Text("Conserver comme inconnue") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        )
    }
}

/**
 * Passe chaque image a ML Kit, puis choisit parmi les blocs de texte reconnus
 * celui qui porte un code de collection (`pickCodeText`, dans `core`) pour un
 * unique appel a `onFrame` par image. Une carte produit plusieurs blocs (nom,
 * regles, illustrateur, code) : appeler `onFrame` pour chacun d'eux rearmerait
 * la machine a chaque fois qu'un bloc sans code est rencontre, et le code
 * n'atteindrait jamais les deux lectures consecutives qui le valident.
 * `paused` est vrai tant qu'un ecran de decision est ouvert.
 */
class CardAnalyzer(
    private val machine: ScanMachine,
    private val currentState: () -> ScanUiState,
    private val onEvent: (ScanEvent) -> Unit
) : ImageAnalysis.Analyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        val state = currentState()
        if (mediaImage == null || state.paused) {
            image.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val codeText = pickCodeText(text.textBlocks.map { it.text })
                val event = machine.onFrame(
                    codeText, state.finish, state.language, state.condition, state.entries
                )
                if (event != null) onEvent(event)
            }
            // Fermer l'image quoi qu'il arrive : sans ce listener, le flux se fige.
            .addOnCompleteListener { image.close() }
    }
}

/** Ce que l'analyseur doit connaitre de l'etat de l'interface a chaque image. */
data class ScanUiState(
    val finish: String,
    val language: String,
    val condition: String,
    val entries: List<Entry>,
    val paused: Boolean
)
