# Riftbound Scanner Android — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une application Android native qui lit le code imprimé des cartes Riftbound au vol avec CameraX et ML Kit, tient la collection et l'exporte en CSV.

**Architecture:** Deux constructions Gradle **indépendantes**. `core/` est une bibliothèque Kotlin JVM sans une ligne d'Android : elle porte toute la logique déjà éprouvée en JavaScript et se teste en local en quelques secondes. `android/` est l'application, qui consomme `core/` par construction composite et n'est compilée que par GitHub Actions, où le SDK Android est présent.

**Tech Stack:** Kotlin 2.0.21, Gradle wrapper 8.10.2 pour `core/`, AGP 9.4.1, CameraX 1.6.2, ML Kit `text-recognition` 16.0.1 (modèle embarqué), Jetpack Compose (BOM 2026.09.00), JUnit 5 via `kotlin("test")`.

**Spec de référence:** `docs/superpowers/specs/2026-09-27-android-natif-design.md`

## Global Constraints

- **JDK 17** est installé à `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`, et `JAVA_HOME` est déjà défini pour l'utilisateur. Aucun SDK Android n'est installé localement, et il ne doit pas être requis pour `core/`.
- `core/` n'a **aucune dépendance Android** et aucune dépendance de production : seul `kotlin("test")` est autorisé, en `testImplementation`.
- Commentaires, messages d'interface et noms de tests en **français**. Identifiants de code en **anglais**.
- **N'écris jamais un fichier Kotlin avec un heredoc de shell.** Le shell de cet environnement mange un antislash sur deux : `Regex("\\d+")` devient `Regex("\d+")`, qui ne compile pas. Utilise l'outil d'écriture de fichiers. Préfère de toute façon `[0-9]` à `\d` dans les expressions régulières.
- Le catalogue `data/cards.json` (1320 cartes, 449 Ko) reste à sa place actuelle et sert de source unique, pour `core/` en test et pour l'application en ressource embarquée.
- Règle directrice du produit : **ne jamais perdre un scan**.
- Chaque tâche finit par un commit. Ne pousse pas sur `main` sans que les tests de `core/` soient verts.

---

## Structure des fichiers

| Fichier | Responsabilité |
|---|---|
| `core/gradlew`, `core/gradle/wrapper/*` | Wrapper Gradle auto-installant, commité |
| `core/build.gradle.kts`, `core/settings.gradle.kts` | Bibliothèque Kotlin JVM, tests JUnit |
| `core/src/main/kotlin/.../CollectorCode.kt` | `canonicalCode`, `parseCollectorCode` |
| `core/src/main/kotlin/.../Card.kt` | Le type `Card`, et `Catalog` (index, rapprochement, candidats) |
| `core/src/main/kotlin/.../Variant.kt` | `variantOf`, `resolveVariant` |
| `core/src/main/kotlin/.../Entries.kt` | `entryKey`, `addScan`, `incrementEntry`, `updateEntry`, `removeEntry` |
| `core/src/main/kotlin/.../Csv.kt` | `toCsv` |
| `core/src/main/kotlin/.../ScanMachine.kt` | Règle des deux lectures et aiguillage |
| `core/src/test/kotlin/...` | Un fichier de test par module ci-dessus |
| `android/settings.gradle.kts` | Construction composite incluant `../core` |
| `android/app/src/main/.../MainActivity.kt` | Point d'entrée, navigation entre les deux écrans |
| `android/app/src/main/.../ScanScreen.kt` | CameraX, analyseur ML Kit, réglages collants |
| `android/app/src/main/.../CollectionScreen.kt` | Liste, corrections, export |
| `android/app/src/main/.../CatalogLoader.kt` | Lecture du catalogue embarqué |
| `android/app/src/main/.../CollectionStore.kt` | Persistance atomique en JSON |
| `android/keystore/debug.keystore` | Clé de signature fixe, commitée (décision assumée) |
| `.github/workflows/android.yml` | Tests `core/`, compilation de l'APK, publication en Release |

---

## Task 1: Squelette de `core/` et chaîne d'outils

**Files:**
- Create: `core/settings.gradle.kts`, `core/build.gradle.kts`, `core/gradle/wrapper/gradle-wrapper.properties`, `core/gradle/wrapper/gradle-wrapper.jar`, `core/gradlew`, `core/gradlew.bat`, `core/.gitignore`
- Create: `core/src/main/kotlin/fr/riftbound/scanner/core/CollectorCode.kt`
- Create: `core/src/test/kotlin/fr/riftbound/scanner/core/CollectorCodeTest.kt`

**Interfaces:**
- Consumes: rien.
- Produces: `canonicalCode(code: String?): String?` — minuscule, zéros de tête retirés du numéro, `null` si l'entrée est `null`.

- [ ] **Step 1: Installer le wrapper Gradle**

Le wrapper se télécharge depuis le dépôt Gradle, ce qui évite d'installer Gradle.

```bash
cd core
mkdir -p gradle/wrapper
GV=8.10.2
curl -sL -o gradle/wrapper/gradle-wrapper.jar "https://raw.githubusercontent.com/gradle/gradle/v${GV}/gradle/wrapper/gradle-wrapper.jar"
curl -sL -o gradlew     "https://raw.githubusercontent.com/gradle/gradle/v${GV}/gradlew"
curl -sL -o gradlew.bat "https://raw.githubusercontent.com/gradle/gradle/v${GV}/gradlew.bat"
chmod +x gradlew
```

Vérifie que le jar fait environ 43 Ko et que `file gradle/wrapper/gradle-wrapper.jar` annonce une archive Zip. Un fichier HTML de quelques centaines d'octets signifie que l'URL a changé.

- [ ] **Step 2: Écrire les fichiers de construction**

`core/gradle/wrapper/gradle-wrapper.properties` :

```
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

`core/settings.gradle.kts` :

```kotlin
rootProject.name = "riftbound-core"
```

`core/build.gradle.kts` :

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
```

`core/.gitignore` :

```
.gradle/
build/
```

- [ ] **Step 3: Écrire le test qui échoue**

`core/src/test/kotlin/fr/riftbound/scanner/core/CollectorCodeTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectorCodeTest {

    @Test
    fun `canonicalCode minuscule et retire les zeros de tete du numero`() {
        assertEquals("unl-29a-219", canonicalCode("UNL-029a-219"))
        assertEquals("sfd-t3", canonicalCode("sfd-t03"))
        assertEquals("unl-121-219", canonicalCode("unl-121-219"))
    }

    @Test
    fun `canonicalCode laisse le total intact`() {
        assertEquals("ven-sp4-006", canonicalCode("VEN-SP4-006"))
    }

    @Test
    fun `canonicalCode rejette une entree nulle`() {
        assertNull(canonicalCode(null))
    }
}
```

- [ ] **Step 4: Lancer le test pour le voir échouer**

Run: `cd core && ./gradlew test`
Expected: FAIL — `Unresolved reference: canonicalCode`.

- [ ] **Step 5: Écrire l'implémentation minimale**

`core/src/main/kotlin/fr/riftbound/scanner/core/CollectorCode.kt` :

```kotlin
package fr.riftbound.scanner.core

private val PREMIER_NOMBRE = Regex("[0-9]+")

/** Forme de reference d'un code : minuscule, sans zeros de tete sur le numero. */
fun canonicalCode(code: String?): String? {
    if (code == null) return null
    val parts = code.lowercase().split("-").toMutableList()
    if (parts.size < 2) return code.lowercase()
    val trouve = PREMIER_NOMBRE.find(parts[1])
    if (trouve != null) {
        val sansZeros = trouve.value.trimStart('0').ifEmpty { "0" }
        parts[1] = parts[1].replaceRange(trouve.range, sansZeros)
    }
    return parts.joinToString("-")
}
```

- [ ] **Step 6: Lancer le test**

Run: `cd core && ./gradlew test`
Expected: PASS, 3 tests. La première exécution télécharge Gradle et Kotlin, comptez une minute ; les suivantes prennent une dizaine de secondes.

- [ ] **Step 7: Commit**

```bash
git add core
git commit -m "feat(core): squelette Kotlin JVM et forme canonique des codes"
```

---

## Task 2: Application Android minimale et chaîne de publication

Cette tâche arrive délibérément **avant** le portage de la logique. Elle prouve que l'utilisateur peut réellement installer un APK produit par GitHub Actions. Si cette chaîne ne fonctionne pas, tout le reste est sans objet, et il vaut mieux le découvrir maintenant.

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/.gitignore`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/kotlin/fr/riftbound/scanner/MainActivity.kt`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/keystore/debug.keystore`
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `core/` via construction composite.
- Produces: un APK installable, publié en Release GitHub.

- [ ] **Step 1: Générer la clé de signature fixe**

La clé est volontairement commitée : c'est ce qui donne une signature stable, donc des mises à jour qui s'installent par-dessus sans effacer la collection. Le mot de passe `android` est celui, public, des clés de débogage Android.

```bash
mkdir -p android/keystore
"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore android/keystore/debug.keystore \
  -storepass android -keypass android \
  -alias riftbound -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Riftbound Scanner, OU=Perso, O=Perso, L=-, S=-, C=FR"
```

- [ ] **Step 2: Écrire les fichiers de construction Android**

`android/settings.gradle.kts` :

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "riftbound-android"
include(":app")
includeBuild("../core")
```

`android/build.gradle.kts` :

```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

`android/gradle.properties` :

```
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`android/.gitignore` :

```
.gradle/
build/
local.properties
```

`android/app/build.gradle.kts` :

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.riftbound.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.riftbound.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("fixe") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "riftbound"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixe")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("fr.riftbound:riftbound-core")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
```

Pour que `implementation("fr.riftbound:riftbound-core")` résolve la construction composite, ajoute dans `core/build.gradle.kts` :

```kotlin
group = "fr.riftbound"
version = "0.1"
```

- [ ] **Step 3: Écrire l'application minimale**

`android/app/src/main/AndroidManifest.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar"
        android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Riftbound</string>
</resources>
```

`android/app/src/main/kotlin/fr/riftbound/scanner/MainActivity.kt` :

```kotlin
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
```

- [ ] **Step 4: Écrire l'atelier de compilation**

`.github/workflows/android.yml` :

```yaml
name: Android
on:
  push:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Tests de la logique metier
        working-directory: core
        run: ./gradlew test
      - name: Compilation de l APK
        working-directory: android
        run: ./gradlew :app:assembleRelease
      - name: Publication
        uses: softprops/action-gh-release@v2
        with:
          tag_name: build-${{ github.run_number }}
          name: Build ${{ github.run_number }}
          files: android/app/build/outputs/apk/release/app-release.apk
```

Le wrapper de `android/` se met en place comme celui de `core/` (mêmes commandes, dossier `android/`), avec la version de Gradle exigée par AGP 9.4.1. **Ne devine pas cette version** : lance la compilation, et si Gradle refuse, le message d'erreur nomme exactement la version requise — utilise celle-là.

- [ ] **Step 5: Vérifier que la chaîne produit un APK**

```bash
git add android .github/workflows/android.yml core/build.gradle.kts
git commit -m "feat(android): application minimale et publication de l APK"
git push
```

Run: `gh run watch --repo TaterFreak/riftbound-scanner --exit-status`
Expected: la compilation passe et une Release `build-N` apparaît avec `app-release.apk` attaché. Si elle échoue, corrige et repousse : **cette tâche n'est pas finie tant qu'un APK n'est pas téléchargeable.**

- [ ] **Step 6: Faire valider l'installation par l'utilisateur**

Donne-lui le lien de la Release. Il doit pouvoir télécharger l'APK sur son téléphone, autoriser l'installation depuis Chrome, installer, et voir l'écran afficher `unl-29a-219`. C'est la preuve que la distribution fonctionne **et** que `core/` est bien embarqué.

Ne passe pas à la suite sans cette confirmation.

---

## Task 3: Lecture du code de collection

**Files:**
- Modify: `core/src/main/kotlin/fr/riftbound/scanner/core/CollectorCode.kt`
- Modify: `core/src/test/kotlin/fr/riftbound/scanner/core/CollectorCodeTest.kt`

**Interfaces:**
- Consumes: `canonicalCode` de la Task 1.
- Produces: `parseCollectorCode(rawOcrText: String?): String?` — renvoie un code canonique ou `null`.

Ce portage reprend `src/recognize/collector-code.js`, qui a été durci par deux tours de revue. Les trois garde-fous ci-dessous sont le résultat de bugs réels : ne les assouplis pas.

- [ ] **Step 1: Écrire les tests qui échouent**

Ajoute à `CollectorCodeTest.kt` :

```kotlin
    @Test
    fun `lit un code propre`() {
        assertEquals("unl-121-219", parseCollectorCode("UNL-121-219"))
    }

    @Test
    fun `accepte les separateurs rencontres a l impression`() {
        for (brut in listOf("UNL 121 219", "UNL/121/219", "UNL·121·219", "UNL – 121 – 219")) {
            assertEquals("unl-121-219", parseCollectorCode(brut), brut)
        }
    }

    @Test
    fun `conserve le suffixe de variante`() {
        assertEquals("unl-116a-219", parseCollectorCode("UNL-116a-219"))
        assertEquals("unl-229*-219", parseCollectorCode("UNL-229*-219"))
    }

    @Test
    fun `corrige les confusions de l OCR`() {
        assertEquals("unl-121-219", parseCollectorCode("UNL-I2I-2I9"))
        assertEquals("unl-29a-219", parseCollectorCode("UNL-O29a-2I9"))
        assertEquals("ogn-12-219", parseCollectorCode("0GN-012-219"))
    }

    @Test
    fun `accepte les codes a prefixe litteral`() {
        assertEquals("sfd-t3", parseCollectorCode("SFD-T03"))
        assertEquals("ven-r6", parseCollectorCode("VEN-R06"))
        assertEquals("ven-sp4-006", parseCollectorCode("VEN-SP4-006"))
    }

    @Test
    fun `ignore le texte qui entoure le code`() {
        assertEquals("unl-121-219", parseCollectorCode("  UNL-121-219   Jonathan Santoro  "))
    }

    @Test
    fun `ne se laisse pas piéger par un texte de regles`() {
        // Bug reel : la premiere correspondance venue donnait "deal-2-2".
        assertEquals("unl-121-219", parseCollectorCode("Deal -2/-2 to a unit. UNL-121-219"))
        assertEquals("unl-121-219", parseCollectorCode("Give -1/-1 until end of turn. UNL-121-219"))
        assertEquals("unl-121-219", parseCollectorCode("ab-12 UNL-121-219"))
        assertNull(parseCollectorCode("Deal -2/-2 to a unit."))
    }

    @Test
    fun `accepte un set inconnu de forme plausible`() {
        // Regle produit : ne jamais perdre un scan. Une extension future doit
        // pouvoir produire une ligne « inconnue » plutot que d etre rejetee.
        assertEquals("rad-12-200", parseCollectorCode("RAD-012-200"))
        assertEquals("zzz-999-999", parseCollectorCode("ZZZ-999-999"))
    }

    @Test
    fun `rejette ce qui n est pas un code`() {
        for (brut in listOf("", "   ", "Bewitching Spirit", "219", "Illustration : Wild Blue Studios")) {
            assertNull(parseCollectorCode(brut), brut)
        }
        assertNull(parseCollectorCode(null))
    }
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `cd core && ./gradlew test`
Expected: FAIL — `Unresolved reference: parseCollectorCode`.

- [ ] **Step 3: Écrire l'implémentation**

Remplace le contenu de `CollectorCode.kt` par ceci, en conservant `canonicalCode` :

```kotlin
package fr.riftbound.scanner.core

private val PREMIER_NOMBRE = Regex("[0-9]+")

/**
 * Prefixes litteraux du catalogue (jetons, promos). Liste fermee volontairement :
 * l'OCR confond 1 et i, donc « i2i » doit se lire 121 et non prefixe « i » puis 21.
 */
val KNOWN_PREFIXES = listOf("sp", "t", "r")

/**
 * Sets du catalogue. Employes comme PREFERENCE et non comme filtre : un set inconnu
 * dont la forme est plausible est accepte, sans quoi les cartes d une extension
 * future seraient perdues en silence.
 */
private val KNOWN_SETS = listOf("jdg", "ogn", "ogs", "opp", "pr", "sfd", "unl", "ven")

private val SEPARATEURS = Regex("[\\s/·•_.,:;|—–]+")
private val NON_RETENU = Regex("[^a-z0-9*-]")
private val TIRETS = Regex("-+")

private val LETTRE_VERS_CHIFFRE = mapOf('o' to '0', 'i' to '1', 'l' to '1', 's' to '5', 'b' to '8', 'z' to '2')
private val CHIFFRE_VERS_LETTRE = mapOf('0' to 'o', '1' to 'i', '5' to 's', '8' to 'b')

private fun versChiffres(s: String) = s.map { LETTRE_VERS_CHIFFRE[it] ?: it }.joinToString("")
private fun versLettres(s: String) = s.map { CHIFFRE_VERS_LETTRE[it] ?: it }.joinToString("")
private fun sansZeros(s: String) = s.trimStart('0').ifEmpty { "0" }

fun canonicalCode(code: String?): String? {
    if (code == null) return null
    val parts = code.lowercase().split("-").toMutableList()
    if (parts.size < 2) return code.lowercase()
    val trouve = PREMIER_NOMBRE.find(parts[1])
    if (trouve != null) parts[1] = parts[1].replaceRange(trouve.range, sansZeros(trouve.value))
    return parts.joinToString("-")
}

private data class Noyau(val prefix: String, val number: String, val variant: String)

/** Separe un segment central en prefixe litteral, numero et suffixe de variante. */
private fun splitCore(core: String): Noyau? {
    // Seules lettres de variante presentes au catalogue : a (112), b (8), * (36).
    val dernier = core.lastOrNull()
    val variant = if (core.length > 1 && dernier != null && dernier in "ab*") dernier.toString() else ""
    val sansVariante = if (variant.isEmpty()) core else core.dropLast(1)

    val prefix = KNOWN_PREFIXES.firstOrNull { sansVariante.startsWith(it) } ?: ""
    val chiffres = versChiffres(sansVariante.substring(prefix.length))
    if (chiffres.isEmpty() || !chiffres.all { it.isDigit() }) return null

    return Noyau(prefix, sansZeros(chiffres), variant)
}

private data class Candidat(val code: String, val hasTotal: Boolean, val position: Int)

private val APRES_SET = Regex("^([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:$|-)")
private val FORME = Regex("(?:^|-)([a-z0-9]{2,4})-([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:-|$)")

fun parseCollectorCode(rawOcrText: String?): String? {
    if (rawOcrText == null) return null

    val nettoye = rawOcrText.lowercase()
        .replace(SEPARATEURS, "-")
        .replace(NON_RETENU, "")
        .replace(TIRETS, "-")
        .trim('-')
    if (nettoye.isEmpty()) return null

    val connus = mutableListOf<Candidat>()

    for (set in KNOWN_SETS) {
        // Cherche aussi la graphie que l'OCR produit pour ce set (o lu 0, i lu 1...).
        val graphies = mutableSetOf(set)
        graphies.add(set.map { c -> CHIFFRE_VERS_LETTRE.entries.firstOrNull { it.value == c }?.key ?: c }.joinToString(""))

        for (graphie in graphies) {
            var pos = nettoye.indexOf(graphie)
            while (pos != -1) {
                val avantOk = pos == 0 || nettoye[pos - 1] == '-'
                val apres = pos + graphie.length
                val apresOk = apres >= nettoye.length || nettoye[apres] == '-'
                if (avantOk && apresOk && apres < nettoye.length) {
                    val reste = nettoye.substring(apres + 1)
                    val m = APRES_SET.find(reste)
                    if (m != null) {
                        val noyau = splitCore(m.groupValues[1])
                        if (noyau != null) {
                            val totalBrut = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { versChiffres(it) }
                            val total = totalBrut?.takeIf { t -> t.all { it.isDigit() } }
                            val tete = set + "-" + noyau.prefix + noyau.number + noyau.variant
                            connus.add(Candidat(if (total != null) "$tete-$total" else tete, total != null, pos))
                        }
                    }
                }
                pos = nettoye.indexOf(graphie, pos + 1)
            }
        }
    }

    val inconnus = mutableListOf<Candidat>()
    if (connus.isEmpty()) {
        for (m in FORME.findAll(nettoye)) {
            val set = versLettres(m.groupValues[1])
            if (!Regex("^[a-z]{2,4}$").matches(set)) continue
            if (set in KNOWN_SETS) continue
            val noyau = splitCore(m.groupValues[2]) ?: continue
            // Un set inconnu n'est accepte qu'avec un numero d'au moins deux chiffres :
            // c'est ce qui distingue « rad-012-200 » d'un « deal-2-2 » parasite.
            if (noyau.number.length < 2) continue
            val totalBrut = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { versChiffres(it) }
            val total = totalBrut?.takeIf { t -> t.all { it.isDigit() } }
            val tete = set + "-" + noyau.prefix + noyau.number + noyau.variant
            inconnus.add(Candidat(if (total != null) "$tete-$total" else tete, total != null, m.range.first))
        }
    }

    val candidats = if (connus.isNotEmpty()) connus else inconnus
    if (candidats.isEmpty()) return null

    // Un code complet (avec total) prime ; a defaut, la correspondance la plus tardive.
    return candidats.sortedWith(
        compareByDescending<Candidat> { it.hasTotal }.thenByDescending { it.position }
    ).first().code
}
```

- [ ] **Step 4: Lancer les tests**

Run: `cd core && ./gradlew test`
Expected: PASS. Si un cas échoue, c'est l'implémentation qu'il faut corriger, jamais l'attente du test.

- [ ] **Step 5: Commit**

```bash
git add core
git commit -m "feat(core): lire le code de collection depuis un texte d OCR"
```

---

## Task 4: Catalogue, index et rapprochement

**Files:**
- Create: `core/src/main/kotlin/fr/riftbound/scanner/core/Card.kt`
- Create: `core/src/test/kotlin/fr/riftbound/scanner/core/CatalogTest.kt`
- Modify: `core/build.gradle.kts` (dépendance JSON **de test uniquement**)

**Interfaces:**
- Consumes: `canonicalCode`.
- Produces:
  - `data class Card(val code: String, val riftcodexId: String, val tcgplayerId: String?, val name: String, val set: String, val setLabel: String, val number: Int?, val rarity: String, val type: String, val domain: List<String>)`
  - `class Catalog(cards: List<Card>)` avec `matchCards(code: String?): List<Card>` et `candidatesFor(code: String?, limit: Int = 5): List<Card>`

- [ ] **Step 1: Ajouter la dépendance JSON de test**

Dans `core/build.gradle.kts`, la section `dependencies` devient :

```kotlin
dependencies {
    testImplementation(kotlin("test"))
    // Uniquement pour charger data/cards.json dans les tests. La bibliotheque
    // elle-meme ne depend de rien : l'application Android fait son propre parsing.
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 2: Écrire les tests qui échouent**

`core/src/test/kotlin/fr/riftbound/scanner/core/CatalogTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Charge le vrai catalogue depuis data/cards.json, a la racine du depot. */
internal fun chargerCatalogueReel(): List<Card> {
    val fichier = File("../data/cards.json")
    val racine = JSONObject(fichier.readText())
    val tableau = racine.getJSONArray("cards")
    return (0 until tableau.length()).map { i ->
        val o = tableau.getJSONObject(i)
        val domaines = o.getJSONArray("domain")
        Card(
            code = o.getString("code"),
            riftcodexId = o.getString("riftcodexId"),
            tcgplayerId = if (o.isNull("tcgplayerId")) null else o.getString("tcgplayerId"),
            name = o.getString("name"),
            set = o.getString("set"),
            setLabel = o.getString("setLabel"),
            number = if (o.isNull("number")) null else o.getInt("number"),
            rarity = o.getString("rarity"),
            type = o.getString("type"),
            domain = (0 until domaines.length()).map { domaines.getString(it) }
        )
    }
}

class CatalogTest {

    private val cartes = chargerCatalogueReel()
    private val catalogue = Catalog(cartes)

    @Test
    fun `le catalogue reel contient 1320 cartes`() {
        assertEquals(1320, cartes.size)
    }

    @Test
    fun `parseCollectorCode relit les 1320 codes du catalogue`() {
        val mauvais = cartes.filter { parseCollectorCode(it.code) != canonicalCode(it.code) }
        assertEquals(emptyList(), mauvais.map { it.code })
    }

    @Test
    fun `matchCards retrouve une carte unique`() {
        val trouvees = catalogue.matchCards("unl-121-219")
        assertEquals(1, trouvees.size)
        assertEquals("Bewitching Spirit", trouvees[0].name)
    }

    @Test
    fun `matchCards retrouve les deux cartes d un couple Metal`() {
        assertEquals(2, catalogue.matchCards("opp-259-298").size)
    }

    @Test
    fun `seize codes du catalogue designent deux cartes`() {
        val ambigus = cartes.groupBy { canonicalCode(it.code) }.filterValues { it.size > 1 }
        assertEquals(16, ambigus.size)
        assertTrue(ambigus.values.all { groupe -> groupe.any { it.name.endsWith("(Metal)") } })
    }

    @Test
    fun `matchCards ignore les zeros de tete et la casse`() {
        assertEquals(1, catalogue.matchCards("UNL-029a-219").size)
        assertEquals(1, catalogue.matchCards("unl-29a-219").size)
    }

    @Test
    fun `matchCards renvoie une liste vide pour un code inconnu`() {
        assertEquals(emptyList(), catalogue.matchCards("zzz-999-999"))
        assertEquals(emptyList(), catalogue.matchCards(null))
    }

    @Test
    fun `candidatesFor propose le voisin numerique le plus proche en tete`() {
        val proches = catalogue.candidatesFor("unl-122-219").map { canonicalCode(it.code) }
        assertEquals("unl-121-219", proches.first())
    }

    @Test
    fun `candidatesFor respecte la limite par defaut de cinq`() {
        assertTrue(catalogue.candidatesFor("unl-122-219").size <= 5)
        assertTrue(catalogue.candidatesFor("unl-122-219", limit = 2).size <= 2)
    }

    @Test
    fun `candidatesFor ne propose rien au-dela de la distance deux`() {
        assertEquals(emptyList(), catalogue.candidatesFor("abc-999-111"))
    }
}
```

- [ ] **Step 3: Lancer les tests pour les voir échouer**

Run: `cd core && ./gradlew test`
Expected: FAIL — `Unresolved reference: Card`.

- [ ] **Step 4: Écrire l'implémentation**

`core/src/main/kotlin/fr/riftbound/scanner/core/Card.kt` :

```kotlin
package fr.riftbound.scanner.core

/** Une carte du catalogue, telle qu'embarquee dans l'application. */
data class Card(
    val code: String,
    val riftcodexId: String,
    val tcgplayerId: String?,
    val name: String,
    val set: String,
    val setLabel: String,
    val number: Int?,
    val rarity: String,
    val type: String,
    val domain: List<String>
)

private val PREMIER_NOMBRE_SEGMENT = Regex("[0-9]+")

/** Le numero de collection, c'est le segment du MILIEU, pas le dernier (taille du set). */
private fun numeroDe(code: String): Int {
    val parts = code.split("-")
    if (parts.size < 2) return 0
    return PREMIER_NOMBRE_SEGMENT.find(parts[1])?.value?.toIntOrNull() ?: 0
}

private fun distance(a: String, b: String): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > 2) return 3
    var precedent = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val courant = IntArray(b.length + 1)
        courant[0] = i
        for (j in 1..b.length) {
            val cout = if (a[i - 1] == b[j - 1]) 0 else 1
            courant[j] = minOf(courant[j - 1] + 1, precedent[j] + 1, precedent[j - 1] + cout)
        }
        precedent = courant
    }
    return precedent[b.length]
}

/**
 * Index du catalogue. Un code peut legitimement designer plusieurs cartes : seize
 * codes opposent une carte normale a sa version Metal.
 */
class Catalog(val cards: List<Card>) {

    private val index: Map<String, List<Card>> =
        cards.groupBy { canonicalCode(it.code) ?: it.code }

    fun matchCards(code: String?): List<Card> {
        val cle = canonicalCode(code) ?: return emptyList()
        return index[cle] ?: emptyList()
    }

    /** Les cartes dont le code est le plus proche, pour l'ecran de lecture douteuse. */
    fun candidatesFor(code: String?, limit: Int = 5): List<Card> {
        val cible = canonicalCode(code) ?: return emptyList()
        val cibleNum = numeroDe(cible)
        return index.keys
            .map { cle -> Triple(cle, distance(cible, cle), kotlin.math.abs(numeroDe(cle) - cibleNum)) }
            .filter { it.second in 1..2 }
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            .take(limit)
            .flatMap { index[it.first].orEmpty() }
    }
}
```

- [ ] **Step 5: Lancer les tests**

Run: `cd core && ./gradlew test`
Expected: PASS. Le test des 1320 codes est le filet principal du projet : s'il échoue, c'est `parseCollectorCode` qu'il faut corriger.

- [ ] **Step 6: Commit**

```bash
git add core
git commit -m "feat(core): catalogue, index et rapprochement des codes"
```

---

## Task 5: Variantes, entrées de collection et CSV

**Files:**
- Create: `core/src/main/kotlin/fr/riftbound/scanner/core/Variant.kt`, `Entries.kt`, `Csv.kt`
- Create: `core/src/test/kotlin/fr/riftbound/scanner/core/VariantTest.kt`, `EntriesTest.kt`, `CsvTest.kt`

**Interfaces:**
- Consumes: `Card`.
- Produces:
  - `variantOf(name: String?): String?`
  - `sealed interface Resolution { data class Unique(val card: Card) : Resolution; data class Ambiguous(val cards: List<Card>) : Resolution }`
  - `resolveVariant(cards: List<Card>, finish: String): Resolution` — lève `IllegalArgumentException` si `finish` n'est ni `"normal"` ni `"metal"`.
  - `data class Entry(...)` et `entryKey(entry: Entry): String`
  - `sealed interface ScanOutcome { data class Added(val index: Int) : ScanOutcome; data class Duplicate(val index: Int) : ScanOutcome }`
  - `addScan(entries: List<Entry>, card: Card?, rawCode: String, finish: String, language: String, condition: String, scannedAt: String): Pair<List<Entry>, ScanOutcome>`
  - `incrementEntry`, `updateEntry`, `removeEntry`, toutes immuables
  - `toCsv(entries: List<Entry>): String`

- [ ] **Step 1: Écrire les tests des variantes**

`VariantTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun carte(nom: String, id: String = nom) = Card(
    code = "opp-259-298", riftcodexId = id, tcgplayerId = "1", name = nom,
    set = "OPP", setLabel = "Promo", number = 259, rarity = "Promo",
    type = "Legend", domain = listOf("Calm")
)

class VariantTest {

    @Test fun `variantOf extrait le suffixe de nom`() {
        assertEquals("Metal", variantOf("Yasuo - Unforgiven (Metal)"))
        assertEquals("Alternate Art", variantOf("Poppy - Paragon (Alternate Art)"))
        assertNull(variantOf("Bewitching Spirit"))
        assertNull(variantOf(null))
    }

    @Test fun `variantOf ignore une parenthese qui n est pas en fin de nom`() {
        assertNull(variantOf("Gold // Buff (jeton) recto"))
    }

    @Test fun `resolveVariant laisse passer une carte unique`() {
        val c = carte("Yasuo - Unforgiven")
        assertEquals(Resolution.Unique(c), resolveVariant(listOf(c), "metal"))
    }

    @Test fun `resolveVariant tranche un couple Metal selon la finition`() {
        val normale = carte("Yasuo - Unforgiven")
        val metal = carte("Yasuo - Unforgiven (Metal)", "metal-id")
        assertEquals(Resolution.Unique(metal), resolveVariant(listOf(normale, metal), "metal"))
        assertEquals(Resolution.Unique(normale), resolveVariant(listOf(normale, metal), "normal"))
    }

    @Test fun `resolveVariant rend la main quand le reglage ne tranche pas`() {
        val a = carte("Carte A", "a")
        val b = carte("Carte B", "b")
        assertEquals(Resolution.Ambiguous(listOf(a, b)), resolveVariant(listOf(a, b), "normal"))
    }

    @Test fun `resolveVariant refuse une finition hors contrat`() {
        val c = carte("Yasuo - Unforgiven")
        assertFailsWith<IllegalArgumentException> { resolveVariant(listOf(c), "foil") }
    }
}
```

- [ ] **Step 2: Lancer les tests pour les voir échouer, puis écrire `Variant.kt`**

Run: `cd core && ./gradlew test` → FAIL (`Unresolved reference: variantOf`).

```kotlin
package fr.riftbound.scanner.core

private val SUFFIXE = Regex("\\(([^()]+)\\)$")

/** Le suffixe entre parentheses en fin de nom, seul marqueur de variante du catalogue. */
fun variantOf(name: String?): String? =
    name?.let { SUFFIXE.find(it)?.groupValues?.get(1) }

sealed interface Resolution {
    data class Unique(val card: Card) : Resolution
    data class Ambiguous(val cards: List<Card>) : Resolution
}

private fun estMetal(card: Card) = variantOf(card.name) == "Metal"

/**
 * Le reglage de finition tranche les seize codes ambigus Normale/Metal du catalogue.
 * Toute autre ambiguite est rendue a l'utilisateur plutot que devinee.
 */
fun resolveVariant(cards: List<Card>, finish: String): Resolution {
    require(finish == "normal" || finish == "metal") {
        "finish doit valoir 'normal' ou 'metal', recu : $finish"
    }
    if (cards.size == 1) return Resolution.Unique(cards[0])
    val voulues = if (finish == "metal") cards.filter(::estMetal) else cards.filterNot(::estMetal)
    return if (voulues.size == 1) Resolution.Unique(voulues[0]) else Resolution.Ambiguous(cards)
}
```

Run: `cd core && ./gradlew test` → PASS.

- [ ] **Step 3: Écrire les tests des entrées**

`EntriesTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val bewitching = Card(
    code = "unl-121-219", riftcodexId = "abc", tcgplayerId = "685592",
    name = "Bewitching Spirit", set = "UNL", setLabel = "Unleashed", number = 121,
    rarity = "Common", type = "Unit", domain = listOf("Chaos")
)

class EntriesTest {

    private fun scan(entries: List<Entry>, card: Card?, raw: String = "unl-121-219",
                     finish: String = "normal", language: String = "en", condition: String = "NM") =
        addScan(entries, card, raw, finish, language, condition, "2026-09-27T10:00:00Z")

    @Test fun `addScan cree une entree complete`() {
        val (entries, outcome) = scan(emptyList(), bewitching)
        assertEquals(ScanOutcome.Added(0), outcome)
        assertEquals(1, entries.size)
        assertEquals("Bewitching Spirit", entries[0].name)
        assertEquals(1, entries[0].quantity)
        assertEquals(false, entries[0].unknown)
    }

    @Test fun `addScan n incremente pas de lui-meme un doublon`() {
        val premier = scan(emptyList(), bewitching).first
        val (entries, outcome) = scan(premier, bewitching)
        assertEquals(ScanOutcome.Duplicate(0), outcome)
        assertEquals(1, entries[0].quantity)
        assertEquals(premier, entries)
    }

    @Test fun `addScan distingue deux cartes differentes de meme code`() {
        // Bug reel : la version Metal disparaissait, absorbee par la ligne normale.
        val metal = bewitching.copy(riftcodexId = "metal-id", name = "Bewitching Spirit (Metal)")
        val apres = scan(emptyList(), bewitching).first
        val (entries, outcome) = scan(apres, metal)
        assertEquals(ScanOutcome.Added(1), outcome)
        assertEquals(2, entries.size)
    }

    @Test fun `addScan distingue les finitions langues et etats`() {
        var e = scan(emptyList(), bewitching).first
        e = scan(e, bewitching, finish = "metal").first
        e = scan(e, bewitching, language = "fr").first
        e = scan(e, bewitching, condition = "EX").first
        assertEquals(4, e.size)
    }

    @Test fun `addScan enregistre une carte inconnue sans la perdre`() {
        val (entries, outcome) = scan(emptyList(), null, raw = "zzz-999-999")
        assertTrue(outcome is ScanOutcome.Added)
        assertEquals(true, entries[0].unknown)
        assertEquals("zzz-999-999", entries[0].rawCode)
        assertEquals("", entries[0].name)
    }

    @Test fun `deux cartes inconnues de codes differents restent distinctes`() {
        val e = scan(emptyList(), null, raw = "zzz-1-1").first
        val (apres, outcome) = scan(e, null, raw = "zzz-2-2")
        assertTrue(outcome is ScanOutcome.Added)
        assertEquals(2, apres.size)
        assertNotEquals(entryKey(apres[0]), entryKey(apres[1]))
    }

    @Test fun `le domaine de la ligne reprend celui de la carte`() {
        // En JavaScript, le tableau `domain` etait partage par reference avec le
        // catalogue et le muter le corrompait. En Kotlin, `List` est en lecture
        // seule : le bug ne peut pas se reproduire. La copie defensive dans
        // `entreeDepuisScan` reste par prudence, et ce test verifie le contenu.
        val (entries, _) = scan(emptyList(), bewitching)
        assertEquals(listOf("Chaos"), entries[0].domain)
    }

    @Test fun `incrementEntry ajoute un exemplaire sans muter`() {
        val avant = scan(emptyList(), bewitching).first
        val apres = incrementEntry(avant, 0)
        assertEquals(2, apres[0].quantity)
        assertEquals(1, avant[0].quantity)
    }

    @Test fun `updateEntry et removeEntry sont immuables`() {
        val avant = scan(emptyList(), bewitching).first
        assertEquals("EX", updateEntry(avant, 0, condition = "EX")[0].condition)
        assertEquals("NM", avant[0].condition)
        assertEquals(emptyList(), removeEntry(avant, 0))
    }
}
```

- [ ] **Step 4: Écrire `Entries.kt`**

```kotlin
package fr.riftbound.scanner.core

/** Une ligne de collection : un exemplaire vendable sous une meme annonce. */
data class Entry(
    val code: String,
    val name: String,
    val set: String,
    val setLabel: String,
    val number: Int?,
    val rarity: String,
    val type: String,
    val domain: List<String>,
    val riftcodexId: String?,
    val tcgplayerId: String?,
    val variant: String?,
    val finish: String,
    val language: String,
    val condition: String,
    val quantity: Int,
    val rawCode: String,
    val scannedAt: String,
    val unknown: Boolean
)

sealed interface ScanOutcome {
    data class Added(val index: Int) : ScanOutcome
    data class Duplicate(val index: Int) : ScanOutcome
}

/**
 * La cle se fonde sur l'identifiant intrinseque de la carte, et non sur le code
 * imprime : seize codes designent deux cartes distinctes, qui ne doivent pas fusionner.
 */
fun entryKey(entry: Entry): String =
    listOf(entry.riftcodexId ?: entry.rawCode, entry.finish, entry.language, entry.condition)
        .joinToString("|")

private fun entreeDepuisScan(
    card: Card?, rawCode: String, finish: String,
    language: String, condition: String, scannedAt: String
): Entry = if (card == null) {
    Entry(
        code = rawCode, name = "", set = "", setLabel = "", number = null,
        rarity = "", type = "", domain = emptyList(), riftcodexId = null, tcgplayerId = null,
        variant = null, finish = finish, language = language, condition = condition,
        quantity = 1, rawCode = rawCode, scannedAt = scannedAt, unknown = true
    )
} else {
    Entry(
        code = card.code, name = card.name, set = card.set, setLabel = card.setLabel,
        number = card.number, rarity = card.rarity, type = card.type,
        // Copie defensive : le catalogue est partage par toute la session.
        domain = card.domain.toList(),
        riftcodexId = card.riftcodexId, tcgplayerId = card.tcgplayerId,
        variant = variantOf(card.name), finish = finish, language = language,
        condition = condition, quantity = 1, rawCode = rawCode,
        scannedAt = scannedAt, unknown = false
    )
}

/**
 * N'incremente jamais d'elle-meme : un doublon est signale et l'utilisateur tranche.
 * C'est ce qui evite qu'une carte restee devant l'objectif s'ajoute en boucle.
 */
fun addScan(
    entries: List<Entry>, card: Card?, rawCode: String, finish: String,
    language: String, condition: String, scannedAt: String
): Pair<List<Entry>, ScanOutcome> {
    val entree = entreeDepuisScan(card, rawCode, finish, language, condition, scannedAt)
    val cle = entryKey(entree)
    val index = entries.indexOfFirst { entryKey(it) == cle }
    return if (index != -1) entries to ScanOutcome.Duplicate(index)
    else (entries + entree) to ScanOutcome.Added(entries.size)
}

fun incrementEntry(entries: List<Entry>, index: Int): List<Entry> =
    entries.mapIndexed { i, e -> if (i == index) e.copy(quantity = e.quantity + 1) else e }

fun updateEntry(
    entries: List<Entry>, index: Int,
    language: String? = null, condition: String? = null, quantity: Int? = null
): List<Entry> = entries.mapIndexed { i, e ->
    if (i != index) e
    else e.copy(
        language = language ?: e.language,
        condition = condition ?: e.condition,
        quantity = quantity ?: e.quantity
    )
}

fun removeEntry(entries: List<Entry>, index: Int): List<Entry> =
    entries.filterIndexed { i, _ -> i != index }
```

Run: `cd core && ./gradlew test` → PASS.

- [ ] **Step 5: Écrire les tests du CSV**

`CsvTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ligne = Entry(
    code = "unl-121-219", name = "Bewitching Spirit", set = "UNL", setLabel = "Unleashed",
    number = 121, rarity = "Common", type = "Unit", domain = listOf("Chaos"),
    riftcodexId = "abc", tcgplayerId = "685592", variant = null, finish = "normal",
    language = "en", condition = "NM", quantity = 2, rawCode = "unl-121-219",
    scannedAt = "2026-09-27T10:00:00Z", unknown = false
)

private fun lignes(csv: String) = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")

class CsvTest {

    @Test fun `commence par un BOM et un en-tete de dix-huit colonnes`() {
        val csv = toCsv(emptyList())
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(18, lignes(csv)[0].split(";").size)
        assertTrue(lignes(csv)[0].startsWith("quantity;name;set;"))
    }

    @Test fun `ecrit une ligne complete`() {
        val cols = lignes(toCsv(listOf(ligne)))[1].split(";")
        assertEquals("2", cols[0])
        assertEquals("Bewitching Spirit", cols[1])
        assertEquals("unl-121-219", cols[5])
    }

    @Test fun `joint les domaines par une barre verticale`() {
        val e = ligne.copy(domain = listOf("Fury", "Order"))
        val entete = lignes(toCsv(listOf(e)))[0].split(";")
        val cols = lignes(toCsv(listOf(e)))[1].split(";")
        assertEquals("Fury|Order", cols[entete.indexOf("domain")])
    }

    @Test fun `laisse la colonne price vide`() {
        val entete = lignes(toCsv(listOf(ligne)))[0].split(";")
        val cols = lignes(toCsv(listOf(ligne)))[1].split(";")
        assertEquals("", cols[entete.indexOf("price")])
    }

    @Test fun `protege les caracteres speciaux`() {
        assertTrue(lignes(toCsv(listOf(ligne.copy(name = "Gold; Buff"))))[1].contains("\"Gold; Buff\""))
        assertTrue(lignes(toCsv(listOf(ligne.copy(name = "Le \"Boss\""))))[1].contains("\"Le \"\"Boss\"\"\""))
    }

    @Test fun `neutralise une injection de formule`() {
        val cols = lignes(toCsv(listOf(ligne.copy(name = "=CMD|calc!A1"))))[1]
        assertTrue(cols.contains("'=CMD"))
    }

    @Test fun `exporte une carte inconnue avec son code brut`() {
        val e = ligne.copy(unknown = true, name = "", code = "zzz-9-9", rawCode = "zzz-9-9",
                           riftcodexId = null, tcgplayerId = null)
        val entete = lignes(toCsv(listOf(e)))[0].split(";")
        val cols = lignes(toCsv(listOf(e)))[1].split(";")
        assertEquals("zzz-9-9", cols[entete.indexOf("raw_code")])
    }

    @Test fun `utilise des fins de ligne CRLF`() {
        assertTrue(toCsv(listOf(ligne)).contains("\r\n"))
    }
}
```

- [ ] **Step 6: Écrire `Csv.kt`**

```kotlin
package fr.riftbound.scanner.core

// Point-virgule et BOM : Excel en configuration francaise ouvre alors le fichier
// correctement d'un double-clic, sans cesser d'etre du CSV standard.

private val COLONNES: List<Pair<String, (Entry) -> Any?>> = listOf(
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

private val A_PROTEGER = Regex("[\";\r\n]")
private val DEBUT_DE_FORMULE = Regex("^[=+\\-@]")

private fun echapper(valeur: Any?): String {
    var texte = valeur?.toString() ?: ""
    // Neutralise l'interpretation en formule a l'ouverture dans un tableur.
    if (DEBUT_DE_FORMULE.containsMatchIn(texte)) texte = "'$texte"
    return if (A_PROTEGER.containsMatchIn(texte)) "\"" + texte.replace("\"", "\"\"") + "\"" else texte
}

fun toCsv(entries: List<Entry>): String {
    val entete = COLONNES.joinToString(";") { it.first }
    val lignes = entries.map { e -> COLONNES.joinToString(";") { echapper(it.second(e)) } }
    return "\uFEFF" + (listOf(entete) + lignes).joinToString("\r\n") + "\r\n"
}
```

- [ ] **Step 7: Lancer toute la suite et committer**

Run: `cd core && ./gradlew test`
Expected: PASS.

```bash
git add core
git commit -m "feat(core): variantes, entrees de collection et export CSV"
```

---

## Task 6: Machine de scan

**Files:**
- Create: `core/src/main/kotlin/fr/riftbound/scanner/core/ScanMachine.kt`
- Create: `core/src/test/kotlin/fr/riftbound/scanner/core/ScanMachineTest.kt`

**Interfaces:**
- Consumes: `Catalog`, `resolveVariant`, `entryKey`, `parseCollectorCode`.
- Produces:
  - `sealed interface ScanEvent` avec `Accept(code, card)`, `Duplicate(code, card, index)`, `Ambiguous(code, cards)`, `Unknown(code, candidates)`
  - `class ScanMachine(catalog: Catalog, confirmFrames: Int = 2)` avec `onFrame(rawText: String?, finish: String, language: String, condition: String, entries: List<Entry>): ScanEvent?`, `decide(...)` et `reset()`

Ces trois règles sont la valeur du module, et chacune vient d'un défaut réel : la confirmation sur deux lectures élimine les faux positifs, l'absence de réémission empêche une carte posée devant l'objectif de s'ajouter en boucle, et le réarmement permet de remontrer la même carte.

- [ ] **Step 1: Écrire les tests**

`ScanMachineTest.kt` :

```kotlin
package fr.riftbound.scanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanMachineTest {

    private val catalogue = Catalog(chargerCatalogueReel())

    private fun rejouer(m: ScanMachine, trames: List<String>, entries: List<Entry> = emptyList()) =
        trames.mapNotNull { m.onFrame(it, "normal", "en", "NM", entries) }

    @Test fun `une seule trame ne suffit pas`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("UNL-121-219")))
    }

    @Test fun `deux trames identiques valident la lecture`() {
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-121-219"))
        assertEquals(1, e.size)
        assertTrue(e[0] is ScanEvent.Accept)
        assertEquals("Bewitching Spirit", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `deux lectures differentes ne valident rien`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-122-219")))
    }

    @Test fun `une carte restee devant l objectif n est pas reemise`() {
        val trames = List(50) { "UNL-121-219" }
        assertEquals(1, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `une trame illisible rearme la machine`() {
        val trames = listOf("UNL-121-219", "UNL-121-219", "", "", "UNL-121-219", "UNL-121-219")
        assertEquals(2, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `deux cartes differentes enchainees sont toutes deux detectees`() {
        val trames = listOf("UNL-121-219", "UNL-121-219", "OPP-259-298", "OPP-259-298")
        assertEquals(2, rejouer(ScanMachine(catalogue), trames).size)
    }

    @Test fun `un code deja en collection remonte un doublon`() {
        val carte = catalogue.matchCards("unl-121-219")[0]
        val (entries, _) = addScan(emptyList(), carte, carte.code, "normal", "en", "NM", "t")
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-121-219", "UNL-121-219"), entries)
        assertTrue(e[0] is ScanEvent.Duplicate)
        assertEquals(0, (e[0] as ScanEvent.Duplicate).index)
    }

    @Test fun `la finition tranche un couple Metal sans interrompre le scan`() {
        val m = ScanMachine(catalogue)
        val e = listOf("OPP-259-298", "OPP-259-298").mapNotNull {
            m.onFrame(it, "metal", "en", "NM", emptyList())
        }
        assertEquals("Yasuo - Unforgiven (Metal)", (e[0] as ScanEvent.Accept).card.name)
    }

    @Test fun `un code inconnu remonte les candidats proches`() {
        val e = rejouer(ScanMachine(catalogue), listOf("UNL-122-219", "UNL-122-219"))
        assertTrue(e[0] is ScanEvent.Unknown)
        val inconnu = e[0] as ScanEvent.Unknown
        assertEquals("unl-122-219", inconnu.code)
        assertTrue(inconnu.candidates.any { it.name == "Bewitching Spirit" })
    }

    @Test fun `un texte illisible ne produit rien`() {
        assertEquals(emptyList(), rejouer(ScanMachine(catalogue), listOf("Jonathan Santoro", "Illustration")))
    }

    @Test fun `decide tranche immediatement sans regle des deux trames`() {
        val v = ScanMachine(catalogue).decide("unl-121-219", "normal", "en", "NM", emptyList())
        assertTrue(v is ScanEvent.Accept)
    }

    @Test fun `reset oublie la lecture en cours`() {
        val m = ScanMachine(catalogue)
        m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList())
        m.reset()
        assertNull(m.onFrame("UNL-121-219", "normal", "en", "NM", emptyList()))
    }
}
```

- [ ] **Step 2: Lancer les tests pour les voir échouer, puis écrire `ScanMachine.kt`**

```kotlin
package fr.riftbound.scanner.core

sealed interface ScanEvent {
    data class Accept(val code: String, val card: Card) : ScanEvent
    data class Duplicate(val code: String, val card: Card, val index: Int) : ScanEvent
    data class Ambiguous(val code: String, val cards: List<Card>) : ScanEvent
    data class Unknown(val code: String, val candidates: List<Card>) : ScanEvent
}

/**
 * Aiguillage du flux de scan a partir d'une suite de textes. Module pur : ni camera,
 * ni Android. C'est ce qui permet de tester le flux complet en rejouant des trames.
 */
class ScanMachine(private val catalog: Catalog, private val confirmFrames: Int = 2) {

    private var pending: String? = null
    private var streak = 0
    private var emitted: String? = null

    fun reset() {
        pending = null
        streak = 0
        emitted = null
    }

    /** Verdict immediat pour un code deja certain : employe par la saisie manuelle. */
    fun decide(
        code: String, finish: String, language: String, condition: String, entries: List<Entry>
    ): ScanEvent {
        val cartes = catalog.matchCards(code)
        if (cartes.isEmpty()) return ScanEvent.Unknown(code, catalog.candidatesFor(code))

        return when (val resolu = resolveVariant(cartes, finish)) {
            is Resolution.Ambiguous -> ScanEvent.Ambiguous(code, resolu.cards)
            is Resolution.Unique -> {
                val carte = resolu.card
                val cle = listOf(carte.riftcodexId, finish, language, condition).joinToString("|")
                val existant = entries.indexOfFirst { entryKey(it) == cle }
                if (existant != -1) ScanEvent.Duplicate(code, carte, existant)
                else ScanEvent.Accept(code, carte)
            }
        }
    }

    fun onFrame(
        rawText: String?, finish: String, language: String, condition: String, entries: List<Entry>
    ): ScanEvent? {
        val code = parseCollectorCode(rawText)

        if (code == null) {
            // Plus rien de lisible : la carte a quitte le cadre, on rearme.
            reset()
            return null
        }

        if (code != pending) {
            pending = code
            streak = 1
            return null
        }

        streak += 1
        if (streak < confirmFrames) return null
        if (emitted == code) return null

        // `emitted` n'est fige qu'apres un verdict reussi, pour qu'une exception
        // ne fasse pas disparaitre la carte en silence.
        val verdict = decide(code, finish, language, condition, entries)
        emitted = code
        return verdict
    }
}
```

- [ ] **Step 3: Lancer toute la suite et committer**

Run: `cd core && ./gradlew test`
Expected: PASS, toute la logique portée est verte.

```bash
git add core
git commit -m "feat(core): machine d etat du flux de scan"
```

---

## Task 7: Écran de scan — CameraX et ML Kit

**Files:**
- Create: `android/app/src/main/kotlin/fr/riftbound/scanner/CatalogLoader.kt`
- Create: `android/app/src/main/kotlin/fr/riftbound/scanner/ScanScreen.kt`
- Modify: `android/app/src/main/kotlin/fr/riftbound/scanner/MainActivity.kt`
- Modify: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/assets/cards.json` (copie de `data/cards.json`)

**Interfaces:**
- Consumes: `Catalog`, `ScanMachine`, `ScanEvent`, `addScan`, `incrementEntry` de `core/`.
- Produces: un écran de scan fonctionnel.

- [ ] **Step 1: Ajouter les dépendances et la permission**

Dans `android/app/build.gradle.kts`, ajoute :

```kotlin
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Modele embarque : l'application lit du texte des la premiere ouverture, hors-ligne.
    implementation("com.google.mlkit:text-recognition:16.0.1")
```

Dans `AndroidManifest.xml`, avant `<application>` :

```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

Copie le catalogue dans les ressources de l'application :

```bash
mkdir -p android/app/src/main/assets
cp data/cards.json android/app/src/main/assets/cards.json
```

- [ ] **Step 2: Écrire le chargeur de catalogue**

`CatalogLoader.kt` lit `assets/cards.json` avec `org.json`, fourni par Android, et construit la liste de `Card`. Il expose une fonction unique :

```kotlin
package fr.riftbound.scanner

import android.content.Context
import fr.riftbound.scanner.core.Card
import fr.riftbound.scanner.core.Catalog
import org.json.JSONObject

/** Lit le catalogue embarque. A appeler une fois, hors du fil principal. */
fun chargerCatalogue(context: Context): Catalog {
    val texte = context.assets.open("cards.json").bufferedReader().use { it.readText() }
    val tableau = JSONObject(texte).getJSONArray("cards")
    val cartes = (0 until tableau.length()).map { i ->
        val o = tableau.getJSONObject(i)
        val domaines = o.getJSONArray("domain")
        Card(
            code = o.getString("code"),
            riftcodexId = o.getString("riftcodexId"),
            tcgplayerId = if (o.isNull("tcgplayerId")) null else o.getString("tcgplayerId"),
            name = o.getString("name"),
            set = o.getString("set"),
            setLabel = o.getString("setLabel"),
            number = if (o.isNull("number")) null else o.getInt("number"),
            rarity = o.getString("rarity"),
            type = o.getString("type"),
            domain = (0 until domaines.length()).map { domaines.getString(it) }
        )
    }
    return Catalog(cartes)
}
```

- [ ] **Step 3: Écrire l'écran de scan**

`ScanScreen.kt` assemble :

- Une `PreviewView` de CameraX dans un `AndroidView` Compose, en plein écran, liée au cycle de vie par `ProcessCameraProvider`, avec `CameraSelector.DEFAULT_BACK_CAMERA`.
- Un `ImageAnalysis` configuré en `STRATEGY_KEEP_ONLY_LATEST`, avec l'analyseur ci-dessous. **L'image entière est analysée, sans viseur.**

C'est la partie la plus facile à rater : oublier de fermer l'`ImageProxy` bloque le flux au bout de deux ou trois images, et le symptôme — un aperçu qui se fige sans erreur — ne ressemble pas à sa cause. Reprends ce code tel quel.

```kotlin
package fr.riftbound.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.riftbound.scanner.core.Entry
import fr.riftbound.scanner.core.ScanEvent
import fr.riftbound.scanner.core.ScanMachine

/**
 * Passe chaque image a ML Kit, puis chaque bloc de texte reconnu a la machine de
 * scan. Le premier bloc qui produit un evenement gagne ; les suivants sont ignores
 * pour cette image. `enPause` est vrai tant qu'un ecran de decision est ouvert.
 */
class AnalyseurDeCartes(
    private val machine: ScanMachine,
    private val etatCourant: () -> EtatScan,
    private val surEvenement: (ScanEvent) -> Unit
) : ImageAnalysis.Analyzer {

    private val lecteur = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val brute = image.image
        val etat = etatCourant()
        if (brute == null || etat.enPause) {
            image.close()
            return
        }

        val entree = InputImage.fromMediaImage(brute, image.imageInfo.rotationDegrees)
        lecteur.process(entree)
            .addOnSuccessListener { texte ->
                for (bloc in texte.textBlocks) {
                    val evenement = machine.onFrame(
                        bloc.text, etat.finish, etat.language, etat.condition, etat.entries
                    )
                    if (evenement != null) {
                        surEvenement(evenement)
                        break
                    }
                }
            }
            // Fermer l'image quoi qu'il arrive : sans ce listener, le flux se fige.
            .addOnCompleteListener { image.close() }
    }
}

/** Ce que l'analyseur doit connaitre de l'etat de l'interface a chaque image. */
data class EtatScan(
    val finish: String,
    val language: String,
    val condition: String,
    val entries: List<Entry>,
    val enPause: Boolean
)
```

Note sur la machine de scan : `onFrame` est appelé une fois par bloc de texte, et non une fois par image. Un bloc illisible réarme donc la machine. C'est voulu — le code de la carte est un bloc stable d'une image à l'autre, et la règle des deux lectures consécutives continue de jouer son rôle. Si tu constates en conditions réelles que des lectures valides sont perdues parce qu'un autre bloc réarme la machine entre deux, passe `onFrame` le **premier bloc qui produit un code non nul** plutôt que tous les blocs, et consigne ce changement.
- La demande de permission caméra via `rememberLauncherForActivityResult` ; en cas de refus, un message clair en français et un bouton ouvrant les réglages de l'application, la saisie manuelle restant accessible.
- Deux réglages collants en haut, **Normale / Metal** et la langue, plus un bouton **torche** (`camera.cameraControl.enableTorch`) et l'**appui pour refaire le point** (`FocusMeteringAction` construit depuis `previewView.meteringPointFactory`).
- Un champ de saisie manuelle du code, qui appelle `machine.decide(...)`.
- Les trois écrans de décision, en `AlertDialog` : doublon, variante ambiguë, code inconnu avec ses candidats. Pendant qu'un dialogue est ouvert, l'analyseur ne doit rien émettre.
- À l'ajout : `HapticFeedbackConstants.CONFIRM` et un `ToneGenerator` bref.

- [ ] **Step 4: Vérifier sur le téléphone**

Pousse, attends la Release, installe l'APK.

Expected : l'aperçu caméra est net à la distance d'une carte tenue en main, et montrer une carte ajoute une ligne en moins d'une seconde, sans avoir à viser quoi que ce soit.

**C'est le critère qui décide du succès du projet.** Si la lecture échoue encore, ne passe pas à la suite : rapporte précisément ce qui se passe, avec le texte que ML Kit renvoie.

- [ ] **Step 5: Commit**

```bash
git add android
git commit -m "feat(android): ecran de scan avec CameraX et ML Kit"
```

---

## Task 8: Collection, persistance et export CSV

**Files:**
- Create: `android/app/src/main/kotlin/fr/riftbound/scanner/CollectionStore.kt`
- Create: `android/app/src/main/kotlin/fr/riftbound/scanner/CollectionScreen.kt`
- Modify: `MainActivity.kt` (navigation entre les deux écrans)

**Interfaces:**
- Consumes: `Entry`, `toCsv`, `updateEntry`, `removeEntry`, `incrementEntry`.
- Produces: `CollectionStore` avec `charger(): List<Entry>`, `enregistrer(entries: List<Entry>)`, `chargerReglages()`, `enregistrerReglages(...)`.

- [ ] **Step 1: Écrire la persistance**

`CollectionStore.kt` sérialise la liste d'`Entry` en JSON avec `org.json`, dans `context.filesDir`. **Écriture atomique obligatoire** : écrire dans `collection.json.tmp`, puis `renameTo("collection.json")`. Une coupure en cours d'écriture laisse alors l'ancien fichier intact.

À la lecture, un fichier illisible ne doit jamais faire planter le démarrage : renomme-le en `collection.corrompue.json`, démarre sur une liste vide, et expose un indicateur que l'écran affichera.

Les réglages de session — finition, langue, état par défaut — vont dans les `SharedPreferences`.

- [ ] **Step 2: Écrire l'écran de collection**

Liste en `LazyColumn` de cartes empilées, une par ligne de collection — pas de tableau, qui déborde sur un écran de téléphone. Chaque carte montre le nom, le code, le set, la finition, et sur une seconde ligne les contrôles : quantité avec un bouton d'incrément, sélecteurs de langue et d'état, bouton de suppression. Les lignes inconnues sont visuellement distinctes et affichent leur code brut.

En tête, le décompte « N lignes, M cartes » et le bouton d'export.

- [ ] **Step 3: Écrire l'export**

L'export utilise `ActivityResultContracts.CreateDocument("text/csv")` pour laisser l'utilisateur choisir la destination, avec un nom par défaut `riftbound-AAAA-MM-JJ.csv`. Écris le résultat de `toCsv(entries)` en UTF-8 dans l'`OutputStream` fourni.

- [ ] **Step 4: Vérifier sur le téléphone**

Scanne trois cartes dont une inconnue, change l'état d'une ligne, **ferme complètement l'application et rouvre-la** : la collection doit être intacte. Exporte le CSV et ouvre-le dans un tableur : les accents doivent être corrects et les colonnes séparées.

- [ ] **Step 5: Commit**

```bash
git add android
git commit -m "feat(android): collection, persistance atomique et export CSV"
```

---

## Task 9: Suppression du code web

À faire **après** que l'application native fonctionne sur le téléphone, jamais avant : tant que le natif n'est pas validé, le web reste le repli.

**Files:**
- Delete: `index.html`, `styles.css`, `sw.js`, `manifest.webmanifest`, `icon-192.png`, `icon-512.png`, `src/app.js`, `src/ui/`, `src/scan/camera.js`, `src/scan/ocr.js`, `src/scan/viewfinder.js`, `scripts/serve.js`, `scripts/make-icons.js`, `.github/workflows/pages.yml`, `test/serve.test.js`, `test/smoke.test.js`, `test/viewfinder.test.js`, `test/store.test.js`
- Delete: `src/recognize/`, `src/collection/`, `src/scan/machine.js` et leurs tests, **une fois seulement que les tests Kotlin équivalents sont verts**
- Modify: `README.md`, `package.json`

**Interfaces:**
- Consumes: rien.
- Produces: un dépôt dont il ne reste que `core/`, `android/`, `catalog/` et `data/`.

- [ ] **Step 1: Vérifier que le portage est complet**

Run: `cd core && ./gradlew test`
Expected: PASS. Compare le nombre de cas couverts avec la suite JavaScript : chaque comportement testé en JS doit avoir son équivalent Kotlin. Liste les écarts avant de supprimer quoi que ce soit.

- [ ] **Step 2: Supprimer**

```bash
git rm -r index.html styles.css sw.js manifest.webmanifest icon-192.png icon-512.png \
  src/ scripts/serve.js scripts/make-icons.js .github/workflows/pages.yml test/
```

Le dossier `catalog/` et ses tests restent : le script de récupération du catalogue tourne sur le poste de développement et n'a aucune raison d'être réécrit en Kotlin. Adapte `package.json` pour ne garder que `catalog:update` et `test`, et ajuste `node --test` au périmètre restant.

- [ ] **Step 3: Désactiver GitHub Pages**

```bash
gh api -X DELETE repos/TaterFreak/riftbound-scanner/pages
```

- [ ] **Step 4: Réécrire le README**

Il doit dire ce qu'est le projet, comment lancer les tests de `core/` en local avec le JDK, où télécharger l'APK, et **pourquoi la version web a été abandonnée** — en renvoyant à la spec. C'est ce qui évitera qu'on la ressuscite par erreur dans six mois.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: supprimer la version web, remplacee par l application native"
```

---

## Task 10: Réglage sur cartes réelles

**Files:**
- Modify: `android/app/src/main/kotlin/fr/riftbound/scanner/ScanScreen.kt`
- Modify: `README.md`

- [ ] **Step 1: Scanner une dizaine de cartes réelles**

Inclure au moins une carte **Metal** et une carte très brillante — ce sont les cas difficiles pour n'importe quel lecteur de texte.

- [ ] **Step 2: Ajuster selon ce qui est observé**

Si des codes sont lus mais mal : c'est `parseCollectorCode` qu'il faut durcir, avec un nouveau cas de test dans `core/`, jamais un correctif dans l'interface.

Si rien n'est lu sur les cartes brillantes : vérifier que la torche aide, et documenter le geste qui marche.

Si des ajouts intempestifs surviennent : passer `confirmFrames` à 3 à la construction de la `ScanMachine`.

- [ ] **Step 3: Consigner les réglages retenus**

Ajoute au `README.md` une section indiquant les valeurs finales, ce qui a été observé sur les cartes brillantes, et le geste qui donne les meilleurs résultats. C'est ce qui évitera de refaire l'expérience à l'aveugle.

- [ ] **Step 4: Commit**

```bash
git add android README.md
git commit -m "chore(scan): regler la lecture sur cartes reelles"
```

---

## Ce que ce plan ne couvre pas

Prix, comptes, synchronisation entre appareils, reconnaissance d'illustration, publication Cardmarket, Play Store. La transformation du CSV vers le format attendu par les extensions Cardmarket fera l'objet d'une spec distincte.
