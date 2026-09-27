# Riftbound Scanner Android — spec de conception

Date : 2026-09-27
Statut : validée, prête pour le plan d'implémentation
Remplace : `2026-09-27-riftbound-scanner-design.md` (version web, abandonnée)

## Pourquoi abandonner la version web

La PWA fonctionne de bout en bout — 125 tests verts, catalogue de 1320 cartes,
déduplication, export CSV — mais elle échoue sur le seul point qui compte : la
qualité d'image. Trois leviers ont été essayés successivement sur l'appareil de
l'utilisateur, sans résultat satisfaisant :

1. Contraintes de résolution élevées (`width: 3840`) et mise au point continue
   demandée à `getUserMedia` puis via `applyConstraints`.
2. Correction de la géométrie du viseur, qui analysait une bande décalée de 135
   pixels par rapport au rectangle affiché — un vrai bug, corrigé et testé.
3. Capture photo plein capteur via `ImageCapture.takePhoto()` au lieu des images
   du flux vidéo.

Verdict de l'utilisateur après ces trois passes : la qualité reste insuffisante
comparée à l'application caméra native. Le plafond est celui de la plateforme web,
pas du code. La lecture de texte par Tesseract compilé en WebAssembly ajoute sa
propre limite : conçu pour des documents scannés, pas pour un flux caméra mobile.

## Décisions de conception

| Sujet | Décision | Raison |
|---|---|---|
| Plateforme | Android natif, Kotlin | Contrôle réel de la caméra, seule voie restante |
| Caméra | CameraX | Autofocus continu, appui pour refaire le point, torche, choix de résolution |
| Lecture de texte | ML Kit Text Recognition, **modèle embarqué** | Conçu pour le flux caméra temps réel ; embarqué pour fonctionner hors-ligne dès la première ouverture |
| Cadrage | **Supprimé** | ML Kit lit l'image entière assez vite ; on cherche le code parmi tous les blocs de texte. Plus rien à aligner. |
| Distribution | APK publié dans les Releases GitHub, compilé par GitHub Actions | Aucun outillage à installer chez l'utilisateur, pas de compte développeur |
| Signature | Clé de débogage fixe **commitée** | Signature stable, donc les mises à jour s'installent par-dessus sans perte de collection. Compromis assumé, voir Risques. |
| Stockage | Fichier JSON interne, écriture atomique | Quelques centaines de lignes ne justifient pas une base de données |
| Dépôt | Le même, le code web est supprimé | L'historique git conserve tout |
| minSdk | 26 (Android 8.0) | Couvre la quasi-totalité du parc, sans contorsions de compatibilité |

## Ce qui est porté, et ce qui est supprimé

### Porté en Kotlin, avec ses tests

Ces éléments ont été vérifiés contre les données réelles et seraient coûteux à
réécrire à l'aveugle.

- **Le catalogue** : `data/cards.json`, 1320 cartes, 449 Ko, produit par le script
  de récupération Riftcodex et dédoublonné par la règle du `tcgplayerId`
  (131 doublons de données écartés, 16 variantes Metal conservées). Il devient un
  fichier de ressources embarqué dans l'APK.
- **La lecture du code** : liste fermée de préfixes (`sp`, `t`, `r`), lettres de
  variante limitées à `a`, `b` et `*`, correction des confusions d'OCR, forme
  canonique sans zéros de tête, préférence aux codes de set connus avec repli sur
  un critère de forme pour les extensions futures, et rejet des faux positifs du
  type `Deal -2/-2`.
- **L'index et le rapprochement** : un code peut désigner plusieurs cartes ;
  candidats proches par distance d'édition, limite 5, départage par proximité
  numérique du numéro de collection.
- **L'arbitrage des variantes** : le réglage de finition tranche les 16 codes
  ambigus Normale/Metal ; toute autre ambiguïté est rendue à l'utilisateur.
- **Les entrées de collection** : clé de regroupement fondée sur l'identifiant
  intrinsèque de la carte, copie défensive des champs partagés, confirmation
  explicite des doublons, conservation des cartes inconnues.
- **L'export CSV** : 18 colonnes, point-virgule, BOM UTF-8, CRLF, neutralisation
  de l'injection de formule.

Les cas de test correspondants sont portés avec le code, **y compris celui qui
confronte la lecture aux 1320 codes réels du catalogue** — il a déjà attrapé deux
bugs et reste le filet principal.

### Supprimé du dépôt

`index.html`, `styles.css`, `sw.js`, `manifest.webmanifest`, `icon-192.png`,
`icon-512.png`, `src/app.js`, `src/ui/`, `src/scan/camera.js`, `src/scan/ocr.js`,
`src/scan/viewfinder.js`, `scripts/serve.js`, `scripts/make-icons.js`,
`.github/workflows/pages.yml`, et les tests devenus sans objet
(`serve.test.js`, `smoke.test.js`, `viewfinder.test.js`, `store.test.js`).

GitHub Pages est désactivé. Le `README.md` explique que la version web est
abandonnée et pourquoi, pour qu'un lecteur futur ne la ressuscite pas par erreur.

Le script de récupération du catalogue (`catalog/`) et ses tests sont **conservés
en JavaScript** : il tourne sur le poste de développement, pas sur le téléphone, et
le réécrire en Kotlin n'apporterait rien.

## Architecture

Deux modules Gradle, séparant strictement ce qui est testable sans appareil.

### `core/` — logique pure, testée sur la machine

Bibliothèque Kotlin sans aucune dépendance Android. Contient la lecture du code,
l'index du catalogue, l'arbitrage des variantes, les entrées de collection et le
CSV. Se teste en JUnit sur la JVM, sans téléphone ni émulateur.

C'est la frontière importante du projet : tout ce qui peut être décidé sans caméra
vit ici.

### `app/` — couche Android

- **CameraX** : aperçu plein écran, `ImageAnalysis` en flux continu, autofocus
  continu, appui pour refaire le point, torche.
- **Analyseur ML Kit** : reçoit chaque image, renvoie les blocs de texte. Chaque
  bloc est passé à la lecture de code de `core/` ; le premier qui donne un code
  valide gagne.
- **Machine de scan** : la règle des deux lectures consécutives est conservée, ainsi
  que le verrou empêchant une carte restée dans le champ de se réajouter. À dix
  images par seconde, la confirmation devient imperceptible.
- **Écrans** : scan et collection.
- **Persistance** : fichier JSON interne, sérialisation kotlinx, écriture atomique
  par fichier temporaire puis renommage.
- **Export** : `ACTION_CREATE_DOCUMENT` pour choisir la destination, et partage.

### `.github/workflows/android.yml`

Lance les tests JVM de `core/`, compile l'APK, le publie dans les Releases. Le
runner Ubuntu de GitHub embarque déjà le SDK Android : aucun outillage local requis.

## Flux de scan

```
analyse continue de l'image entiere
  └─ un bloc de texte donne-t-il un code valide ?
       ├─ non  → rien
       └─ oui  → le meme code deux analyses de suite ?
            ├─ non  → rien
            └─ oui  → deja signale et carte toujours dans le champ ?
                 ├─ oui → rien
                 └─ non → code trouve au catalogue ?
                      ├─ non        → ligne « inconnue », code brut conserve
                      ├─ plusieurs  → ecran de choix de variante
                      └─ une seule  → deja en collection ?
                           ├─ non → ajout, vibration + son
                           └─ oui → « deja scannee xN — ajouter un exemplaire ? »
```

Réglages collants, persistés : **Normale / Metal** et **langue**. La finition
tranche automatiquement les 16 codes ambigus.

## Gestion des erreurs

Règle directrice inchangée : **ne jamais perdre un scan**.

| Situation | Comportement |
|---|---|
| Code absent du catalogue | Ligne « inconnue » conservée avec le code brut, exportée au CSV |
| Permission caméra refusée | Message explicite et bouton vers les réglages système ; la saisie manuelle du code reste disponible |
| Fichier de collection corrompu | Sauvegarde du fichier fautif à côté, démarrage sur une collection vide, message à l'utilisateur — jamais un plantage au lancement |
| Échec d'écriture | L'écriture atomique garantit que l'ancien fichier reste intact |

## Tests

**Unitaires JVM, dans `core/`** : lecture de codes bruités, rapprochement et
candidats, arbitrage des variantes, déduplication et quantité, génération du CSV
avec échappement et BOM. Et la confrontation de la lecture aux **1320 codes réels**
du catalogue embarqué.

**Fonctionnels, dans `app/`** : la machine de scan reçoit une séquence de textes
simulant les sorties de ML Kit, et les quatre issues sont vérifiées — confirmation
sur deux lectures, absence de réémission, doublon, code inconnu, ambiguïté.

**Manuels, irréductibles** : scanner une dizaine de cartes réelles, dont une Metal
et une très brillante, et vérifier la netteté, la vitesse et le taux de lecture.
C'est précisément ce que la version web n'a pas su faire, et c'est le seul critère
qui décide du succès de ce projet.

## Hors périmètre

Prix, comptes, synchronisation entre appareils, reconnaissance d'illustration,
publication Cardmarket, Play Store.

## Risques connus

1. **Signature publique.** La clé de débogage commitée rend la signature stable,
   donc les mises à jour s'installent sans perte de collection, mais elle est
   publique par construction : n'importe qui pourrait produire un APK signé à
   l'identique. Le risque reste théorique tant que l'APK est téléchargé depuis les
   Releases du dépôt de l'utilisateur, mais il interdit toute publication ultérieure
   sur le Play Store sans changer de clé — ce qui obligerait alors à désinstaller
   puis réinstaller.
2. **Installation hors magasin.** Android affiche un avertissement et exige
   d'autoriser une fois l'installation depuis Chrome. Aucune mise à jour
   automatique : il faut revenir chercher le lien à chaque version.
3. **Qualité de lecture non garantie.** ML Kit est nettement supérieur à Tesseract
   sur un flux caméra, mais les cartes Metal et les surfaces très réfléchissantes
   restent le cas difficile. La torche et l'appui pour refaire le point sont là pour
   ça, et la saisie manuelle demeure le filet.
4. **Poids de l'APK.** Modèle ML Kit embarqué plus catalogue : une quinzaine de
   mégaoctets attendus. Acceptable, et c'est le prix du fonctionnement hors-ligne
   immédiat.
