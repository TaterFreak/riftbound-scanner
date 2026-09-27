# Riftbound Scanner — spec de conception

Date : 2026-09-27
Statut : validée, prête pour le plan d'implémentation

## Problème

Inventorier une collection de cartes Riftbound à la main est lent et fastidieux.
L'objectif immédiat : scanner une pile de cartes au téléphone, consulter la liste
obtenue, et exporter un CSV. L'objectif à terme : réutiliser ce CSV pour mettre la
collection en vente sur Cardmarket.

Ce document couvre uniquement l'objectif immédiat. La mise en vente fera l'objet
d'une spec séparée.

## Décisions de conception

| Sujet | Décision | Raison |
|---|---|---|
| Plateforme | PWA ouverte dans Chrome Android | Accès caméra, pas de store, itération immédiate, cohérent avec la stack JS existante |
| Identification | OCR du numéro de collection imprimé en bas de carte | Identifie la version exacte, contrairement au nom qui est ambigu entre sets |
| Flux de scan | Hybride : ajout automatique si lecture nette et carte inédite, écran de confirmation sinon | Rapide sur le cas courant, sûr sur les cas limites |
| Catalogue | Snapshot JSON figé au build depuis l'API Riftcodex | Fonctionne hors-ligne, aucun rate-limit pendant un scan |
| Foil | Réglage de session collant, plus liste d'exceptions maintenue à la main | L'utilisateur scanne ses foils par paquets ; ni l'OCR ni le catalogue ne distinguent un foil |
| Langue | Détection best-effort par mots-outils, adossée à un réglage de session | Aucune base publique ne fournit les noms traduits |
| État | Défaut NM, modifiable ligne par ligne après coup | Non déterminable visuellement ; ne doit pas ralentir le scan |
| Build | ESM natif, aucun bundler, `node --test` | Aligné sur les conventions du dépôt `news` |

## Architecture

Quatre unités, séparant strictement la logique pure des entrées-sorties.

### `catalog/` — acquisition du catalogue (Node, hors runtime)

Script exécuté à la demande via `npm run catalog:update`. Il parcourt les pages de
l'API publique Riftcodex (`https://api.riftcodex.com/cards`, sans authentification)
et écrit `data/cards.json`.

Champs conservés par carte : `id`, `name`, `collector_number`, `set`, `rarity`,
`type`, `domain`, `tcgplayer_id`.

**Limite assumée** : Riftcodex ne publie aucune information de finition. Le catalogue
ne peut donc pas dire qu'une carte n'existe qu'en foil. Cette connaissance vit dans
`data/foil-only.json`, un fichier d'exceptions maintenu à la main, vide au départ, qui
liste des raretés entières ou des cartes individuelles par `set` + `number`. Le script
de mise à jour ne le touche jamais. Tant qu'il est vide, le réglage de session fait
foi pour toutes les cartes.

Le snapshot est commité dans le dépôt. L'application ne contacte jamais d'API à
l'exécution.

**Garde de santé** : le script refuse d'écraser un snapshot existant si la
récupération ramène moins de 80 % du nombre de cartes actuel, ou si un champ
obligatoire manque sur plus de 1 % des cartes. Il écrit alors un rapport et sort en
erreur sans toucher au fichier.

### `src/recognize/` — logique de reconnaissance (pure, sans I/O)

- `parseCollectorCode(rawOcrText) -> { set, number } | null`
  Normalise le bruit d'OCR : confusions `0`/`O` et `1`/`I`/`l`, espaces parasites,
  suffixe `/219` du total de set, casse mixte. Rejette ce qui ne ressemble pas à un
  code plutôt que de deviner.
- `matchCard(code, catalog) -> card | null`
  Rapprochement exact sur `set` + `number`.
- `candidatesFor(rawOcrText, catalog) -> card[]`
  Les trois cartes les plus proches, pour l'écran de lecture douteuse.
- `detectLanguage(rawOcrText) -> 'fr' | 'en' | null`
  Classification par mots-outils. Retourne `null` plutôt que de trancher à
  l'aveugle quand le texte est trop court.

### `src/scan/` — caméra et OCR (I/O)

`getUserMedia` avec `facingMode: 'environment'`. Un viseur rectangulaire est affiché
en surimpression sur le flux vidéo. Toutes les ~400 ms, la bande basse de l'image
(la zone du viseur, soit environ 5 % de la surface) est découpée sur un canvas et
transmise à Tesseract.js configuré avec une liste blanche de caractères limitée aux
majuscules, aux chiffres, au tiret et à la barre oblique.

Restreindre à la fois la zone et l'alphabet est ce qui rend l'OCR utilisable sur
téléphone. Le moteur OCR est injecté, ce qui permet de le remplacer par un faux dans
les tests.

**Règle anti-faux-positif** : un code n'est accepté que s'il est lu à l'identique sur
deux trames consécutives.

### `src/collection/` — collection et export (I/O)

État persisté en IndexedDB, restauré à l'ouverture. Opérations : ajout avec
déduplication, modification d'une ligne, suppression, génération du CSV.

La logique de déduplication et la génération du CSV sont des fonctions pures,
testées séparément du stockage.

## Flux de scan

```
viseur ouvert en continu
  └─ code lu deux trames d'affilée ?
       ├─ non  → rien, aucun retour sonore, l'utilisateur recadre
       └─ oui  → code trouvé dans le catalogue ?
            ├─ non  → ligne « inconnue » conservée avec le code brut,
            │         suggestion de mettre à jour le catalogue
            └─ oui  → cette carte est-elle déjà dans la collection ?
                 ├─ non  → ajout, bip, le viseur continue
                 └─ oui  → figer : « Déjà scannée ×N — ajouter un exemplaire ? »
```

Lecture ambiguë (code partiel, plusieurs correspondances) : affichage des trois
candidats les plus proches, choix au doigt.

Deux réglages collants en haut de l'écran s'appliquent à tous les scans suivants
jusqu'à changement : **Foil / Non-foil** et **Langue**. Une carte listée dans
`foil-only.json` est marquée foil quel que soit le réglage.

La saisie manuelle du code au clavier reste accessible en permanence, et sert aussi
de repli si la caméra est indisponible.

## Modèle de données

Une entrée de collection :

| Champ | Origine |
|---|---|
| `name`, `set`, `collector_number`, `rarity`, `type`, `domain` | catalogue |
| `riftcodex_id`, `tcgplayer_id` | catalogue |
| `quantity` | déduplication au scan |
| `foil` | réglage de session, forcé si listée dans `foil-only.json` |
| `language` | détection, à défaut réglage de session |
| `condition` | `NM` par défaut, éditable |
| `scanned_at` | horodatage |
| `raw_code` | rempli uniquement pour les lignes inconnues |

## Export CSV

Une colonne par champ ci-dessus, plus une colonne `price` laissée vide.

Séparateur **point-virgule**, encodage **UTF-8 avec BOM** : s'ouvre correctement d'un
double-clic dans Excel en configuration française tout en restant du CSV standard.

**Sur Cardmarket** : le site ne propose pas d'import CSV natif pour la mise en vente.
Cela passe par des extensions navigateur tierces (Cardmarket Bulk Import, TCG
PowerTools), qui attendent au minimum extension, numéro de collection, quantité et
prix, la langue et l'état étant optionnels. Les colonnes ci-dessus sont un
sur-ensemble de ces besoins : la transformation vers le format attendu par ces outils
sera un script court, pas une refonte. Aucun fichier directement téléversable sur
Cardmarket n'est promis ici.

## Gestion des erreurs

Règle directrice : **ne jamais perdre un scan**.

| Situation | Comportement |
|---|---|
| Code absent du catalogue | Ligne « inconnue » conservée avec `raw_code`, exportée dans le CSV, suggestion de mise à jour du catalogue |
| Caméra refusée ou page servie en HTTP | Message explicite, bascule sur la saisie manuelle |
| OCR illisible | Aucun retour, aucun bruit |
| Mise à jour du catalogue anormale | Snapshot existant préservé, rapport écrit, sortie en erreur |
| IndexedDB indisponible | Avertissement clair que la session ne survivra pas à la fermeture |

## Tests

**Unitaires** (`node --test`, sans navigateur) : `parseCollectorCode` sur des chaînes
OCR réalistes et bruitées, `matchCard`, `candidatesFor`, `detectLanguage`, la
déduplication et le calcul de quantité, la génération du CSV incluant l'échappement
et le BOM, et la garde de santé du script de catalogue.

**Fonctionnels** : un faux moteur OCR rejoue une séquence de textes de trames et le
flux complet est vérifié — confirmation sur deux trames, ajout automatique, écran de
doublon, écran de candidats, ligne inconnue.

**Manuels, irréductibles** : scanner une dizaine de cartes réelles, dont au moins une
foil et une carte brillante, pour régler la géométrie du viseur et le seuil de
confiance. Aucun test automatisé ne remplace cette passe.

## Déploiement

Fichiers statiques servis par GitHub Pages, en HTTPS — requis par Chrome pour
autoriser la caméra. Service worker mettant en cache le WASM de Tesseract, le modèle
de langue et `cards.json`, pour un fonctionnement hors-ligne après la première
ouverture.

En développement, le téléphone atteint le serveur local via le port forwarding de
`chrome://inspect`, qui fait passer `localhost` pour un contexte sécurisé.

## Hors périmètre de cette version

Prix, comptes utilisateur, synchronisation entre appareils, reconnaissance
d'illustration, publication sur Cardmarket.

## Risques connus

1. **Poids initial** — Tesseract.js représente quelques mégaoctets de WASM et de
   modèle à télécharger à la première ouverture. Mitigé par le cache du service
   worker, mais la première ouverture sera lente.
2. **Foils et reflets** — cas le plus difficile pour l'OCR. Mitigé par l'écran de
   candidats et la saisie manuelle, pas résolu.
3. **Dépendance à Riftcodex** — API communautaire sans garantie de pérennité. Le
   snapshot commité protège l'usage quotidien ; l'acquisition est isolée dans un
   seul fichier, remplaçable par une autre source sans toucher au reste.
