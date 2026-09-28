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
import androidx.compose.runtime.rememberUpdatedState
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
import fr.riftbound.scanner.core.CommitResult
import fr.riftbound.scanner.core.Entry
import fr.riftbound.scanner.core.ScanEvent
import fr.riftbound.scanner.core.ScanMachine
import fr.riftbound.scanner.core.pickCodeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ecran de scan. La collection et les reglages de session (finition, langue,
 * etat par defaut) vivent au niveau de l'application : cet ecran les recoit
 * en parametre pour affichage et notifie ses changements de reglages.
 *
 * Pour la collection, l'ecran ne renvoie plus de liste calculee : il
 * demande a l'appelant (`onCommit`) d'appliquer la decision sur la
 * collection courante et de renvoyer ce qui s'est passe. C'est ce qui
 * empeche une liste capturee trop tot de venir ecraser la vraie
 * collection (voir le commentaire au point de capture, plus bas).
 */
@Composable
fun ScanScreen(
    entries: List<Entry>,
    onCommit: (card: Card?, rawCode: String, finish: String, language: String, condition: String) -> CommitResult,
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
        onCommit = onCommit,
        settings = settings,
        onSettingsChange = onSettingsChange
    )
}

@Composable
private fun ScanScreenContent(
    catalog: Catalog,
    entries: List<Entry>,
    onCommit: (card: Card?, rawCode: String, finish: String, language: String, condition: String) -> CommitResult,
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

    // `entries` et `condition` (derive de `settings`, lui-meme un simple
    // parametre) sont des valeurs ordinaires, pas des etats de snapshot
    // (`mutableStateOf`). Plus bas, `CardAnalyzer` est construit a
    // l'interieur du `factory` d'un `AndroidView`, qui ne s'execute qu'UNE
    // SEULE FOIS : les lambdas qui y capturent une valeur ordinaire la
    // figent a jamais a ce qu'elle valait a la toute premiere composition.
    // C'est exactement ce qui causait un bug reel et critique : `entries`
    // restait fige a la liste vide du demarrage, si bien que chaque scan
    // recalculait la collection a partir de rien (une seule carte
    // retrouvee au lieu de dix) et qu'aucun doublon n'etait jamais detecte.
    // `rememberUpdatedState` cree un etat qui, lui, se met a jour a chaque
    // recomposition et reste lisible correctement meme depuis une fermeture
    // plus ancienne : c'est le remede pour toute valeur lue par
    // `currentState` (plus bas) qui n'est pas deja un `mutableStateOf`.
    // Toute nouvelle valeur ajoutee a `currentState` doit passer par le
    // meme mecanisme, sous peine de reintroduire la meme classe de bug.
    val currentEntries = rememberUpdatedState(entries)
    val currentCondition = rememberUpdatedState(settings.condition)

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
    var cardAnalyzerRef by remember { mutableStateOf<CardAnalyzer?>(null) }
    // Vrai des que l'ecran de scan est quitte. `cameraProviderRef` et
    // `analysisExecutorRef` ne sont assignes que dans le callback
    // asynchrone de `cameraProviderFuture`, plus bas : si l'utilisateur
    // change d'onglet avant que ce callback ne s'execute, cet `onDispose`
    // s'execute alors qu'ils sont encore nuls et ne delie rien. Le
    // callback s'execute ensuite quand meme et lie la camera au cycle de
    // vie de l'Activity - qui reste RESUMED quel que soit l'onglet affiche
    // - laissant un analyseur actif capable d'ajouter des cartes pendant
    // que l'utilisateur regarde un autre ecran. Ce drapeau, consulte par
    // le callback avant de lier quoi que ce soit, ferme cette fenetre quel
    // que soit l'ordre d'execution des deux.
    val disposed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProviderRef?.unbindAll()
            analysisExecutorRef?.shutdown()
            cardAnalyzerRef?.close()
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

    fun formatAdditionMessage(result: CommitResult): String {
        val entry = when (result) {
            is CommitResult.Added -> result.entries[result.index]
            is CommitResult.Incremented -> result.entries[result.index]
        }
        return when (result) {
            is CommitResult.Added ->
                if (entry.unknown) "Code ${entry.rawCode} conservé comme carte inconnue"
                else "${entry.name} ajoutée"
            is CommitResult.Incremented -> "${entry.name} — quantité portée à ${result.quantity}"
        }
    }

    // Seul point d'appel qui fait entrer une carte dans la collection, quel
    // que soit le chemin (scan direct, confirmation de doublon, choix de
    // variante) : l'ecran ne calcule plus lui-meme la nouvelle liste, il
    // demande a l'appelant (onCommit, tenu par MainActivity) d'appliquer la
    // decision sur la collection courante et de dire ce qui s'est passe.
    fun commitAndNotify(card: Card?, rawCode: String) {
        val result = onCommit(card, rawCode, finish, language, currentCondition.value)
        confirmAdditionFeedback()
        showAdditionMessage(formatAdditionMessage(result))
    }

    fun handleEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.Accept -> commitAndNotify(event.card, event.code)

            is ScanEvent.Duplicate, is ScanEvent.Ambiguous, is ScanEvent.Unknown -> {
                pendingEvent = event
            }
        }
    }

    fun submitManualCode() {
        val code = manualCode.trim()
        if (code.isEmpty()) return
        handleEvent(scanMachine.decide(code, finish, language, currentCondition.value, currentEntries.value))
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
                        // L'ecran a peut-etre deja ete quitte pendant que cette
                        // future s'executait : ne rien lier dans ce cas (voir
                        // le commentaire sur `disposed`, plus haut).
                        if (disposed.get()) return@addListener

                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(newPreviewView.surfaceProvider)
                        }

                        val analysisExecutor = Executors.newSingleThreadExecutor()

                        val analyzer = CardAnalyzer(
                            machine = scanMachine,
                            currentState = {
                                ScanUiState(
                                    finish = finish,
                                    language = language,
                                    condition = currentCondition.value,
                                    entries = currentEntries.value,
                                    paused = pendingEvent != null
                                )
                            },
                            onEvent = { event -> mainExecutor.execute { handleEvent(event) } }
                        )

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }

                        cameraProvider.unbindAll()
                        val boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )

                        if (disposed.get()) {
                            // Dispose survenue pendant qu'on liait : delier
                            // immediatement plutot que de laisser tourner un
                            // analyseur actif que plus personne ne surveille.
                            cameraProvider.unbindAll()
                            analysisExecutor.shutdown()
                            analyzer.close()
                            return@addListener
                        }

                        cameraProviderRef = cameraProvider
                        analysisExecutorRef = analysisExecutor
                        cardAnalyzerRef = analyzer
                        camera = boundCamera
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
            Text(text = "${currentEntries.value.sumOf { it.quantity }} carte(s)", color = Color.White)
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
            onConfirmDuplicate = { duplicate ->
                commitAndNotify(duplicate.card, duplicate.code)
                pendingEvent = null
                scanMachine.reset()
            },
            onChooseCard = { code, card ->
                commitAndNotify(card, code)
                pendingEvent = null
                scanMachine.reset()
            },
            onKeepUnknown = { code ->
                commitAndNotify(null, code)
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
    onConfirmDuplicate: (ScanEvent.Duplicate) -> Unit,
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
                // `event` est deja affine en `ScanEvent.Duplicate` par ce
                // `when` : le transmettre directement evite tout cast a
                // l'appelant.
                TextButton(onClick = { onConfirmDuplicate(event) }) { Text("Incrementer") }
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

    /**
     * Libere le client ML Kit. A appeler quand l'ecran de scan est quitte : la
     * navigation entre onglets recree cette classe a chaque retour sur
     * l'onglet Scanner (voir `AndroidView.factory`, execute une seule fois
     * par instance d'ecran), et sans cet appel, chaque aller-retour laisse un
     * nouveau client ML Kit ouvert sans jamais fermer les precedents.
     */
    fun close() {
        textRecognizer.close()
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
