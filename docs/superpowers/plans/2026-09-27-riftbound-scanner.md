# Riftbound Scanner — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une PWA ouverte dans Chrome Android qui lit au vol le code imprimé des cartes Riftbound, construit la collection et l'exporte en CSV.

**Architecture:** Toute la reconnaissance est de la logique pure testable sans navigateur (`src/recognize/`, `src/scan/machine.js`), isolée derrière des adaptateurs d'entrées-sorties injectés (caméra, OCR, IndexedDB). Le catalogue est un instantané JSON figé au build depuis l'API Riftcodex, commité dans le dépôt, si bien que l'application ne contacte aucune API à l'exécution.

**Tech Stack:** Node ≥ 20, ESM natif, aucun bundler, `node --test`, Tesseract.js chargé dans le navigateur, GitHub Pages.

**Spec de référence:** `docs/superpowers/specs/2026-09-27-riftbound-scanner-design.md`

## Global Constraints

- Node ≥ 20. `"type": "module"` partout. Aucune syntaxe CommonJS.
- Aucune dépendance npm en production. Tesseract.js est chargé par le navigateur, pas empaqueté.
- Tests avec `node --test` uniquement. Aucun framework de test tiers.
- Les modules de `src/recognize/`, `src/collection/entries.js`, `src/collection/csv.js`, `src/scan/machine.js` et `catalog/normalize.js` sont **purs** : aucun `fetch`, aucun accès au DOM, aucune horloge, aucun accès disque. Tout ce qui varie est passé en argument.
- Interface et commentaires en français, identifiants de code en anglais.
- Le CSV est en **point-virgule**, encodé **UTF-8 avec BOM** (`﻿`).
- Le code de rapprochement est le `riftbound_id` Riftcodex, minuscule, zéros de tête retirés du numéro.
- Un scan n'est jamais perdu : un code illisible au catalogue produit une ligne « inconnue » conservée et exportée.
- La fixture `test/fixtures/catalog-sample.json` existe déjà dans le dépôt : 10 cartes réelles couvrant le doublon de données, le couple Metal, les suffixes `a` et `*`, l'id irrégulier de jeton et le numéro à zéro de tête. Ne pas la régénérer.

---

## Structure des fichiers

| Fichier | Responsabilité |
|---|---|
| `package.json` | Scripts et métadonnées. Aucune dépendance. |
| `scripts/serve.js` | Serveur statique de développement, `node:http` seul. |
| `catalog/normalize.js` | **Pur.** `normalizeCard`, `dedupeByCode`. |
| `catalog/update.js` | I/O. Pagination de l'API, garde de santé, écriture de `data/cards.json`. |
| `src/recognize/collector-code.js` | **Pur.** `parseCollectorCode`, `canonicalCode`. |
| `src/recognize/match.js` | **Pur.** `buildIndex`, `matchCards`, `candidatesFor`. |
| `src/recognize/variant.js` | **Pur.** `variantOf`, `resolveVariant`. |
| `src/recognize/language.js` | **Pur.** `detectLanguage`. |
| `src/collection/entries.js` | **Pur.** `addScan`, `incrementEntry`, `updateEntry`, `removeEntry`. |
| `src/collection/csv.js` | **Pur.** `toCsv`. |
| `src/collection/store.js` | I/O. Persistance IndexedDB. |
| `src/scan/machine.js` | **Pur.** `createScanMachine` — règle des deux trames et aiguillage. |
| `src/scan/camera.js` | I/O. `getUserMedia`, découpe du viseur. |
| `src/scan/ocr.js` | I/O. Adaptateur Tesseract.js. |
| `src/ui/scan-view.js` | Écran de scan, réglages collants, écrans de confirmation. |
| `src/ui/list-view.js` | Liste, édition, export. |
| `src/app.js` | Câblage. |
| `index.html`, `styles.css`, `manifest.webmanifest`, `sw.js` | Coquille PWA. |

---

## Task 1: Squelette du dépôt et serveur de développement

**Files:**
- Create: `package.json`, `.gitignore`, `README.md`, `scripts/serve.js`, `test/smoke.test.js`

**Interfaces:**
- Consumes: rien.
- Produces: `npm test` exécute `node --test`. `npm run dev` sert la racine du dépôt sur le port 8080.

- [ ] **Step 1: Écrire le test de fumée**

`test/smoke.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('le dépôt est un projet ESM sans dépendance de production', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.type, 'module')
  assert.equal(pkg.dependencies, undefined)
})

test('la fixture de catalogue couvre les cas tordus', async () => {
  const url = new URL('./fixtures/catalog-sample.json', import.meta.url)
  const cards = JSON.parse(await readFile(url, 'utf8'))
  const codes = cards.map((c) => c.riftbound_id)
  assert.equal(codes.filter((c) => c === 'ven-164-166').length, 2, 'doublon de données')
  assert.equal(codes.filter((c) => c === 'opp-259-298').length, 2, 'couple Metal')
  assert.ok(codes.includes('sfd-t03'), 'id irrégulier')
  assert.ok(codes.includes('unl-029a-219'), 'numéro à zéro de tête')
  assert.ok(codes.includes('unl-229*-219'), 'suffixe signature')
})
```

- [ ] **Step 2: Lancer le test pour le voir échouer**

Run: `node --test`
Expected: FAIL — `package.json` n'existe pas encore (`ENOENT`).

- [ ] **Step 3: Écrire `package.json`**

```json
{
  "name": "riftbound-scanner",
  "version": "0.1.0",
  "description": "Scanner de cartes Riftbound au téléphone, avec export CSV.",
  "type": "module",
  "private": true,
  "engines": { "node": ">=20" },
  "scripts": {
    "test": "node --test",
    "test:watch": "node --test --watch",
    "dev": "node scripts/serve.js",
    "catalog:update": "node catalog/update.js"
  },
  "license": "MIT"
}
```

- [ ] **Step 4: Écrire `.gitignore`**

```
node_modules/
.DS_Store
*.log
```

`data/cards.json` n'y figure pas : l'instantané du catalogue est délibérément commité.

- [ ] **Step 5: Écrire `scripts/serve.js`**

```js
// Serveur statique minimal pour le développement. Aucune dépendance.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'

const ROOT = new URL('..', import.meta.url).pathname
const PORT = Number(process.env.PORT ?? 8080)
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
}

createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  const rel = normalize(path === '/' ? '/index.html' : path).replace(/^([/\\])+/, '')
  try {
    const body = await readFile(join(ROOT, rel))
    res.writeHead(200, { 'content-type': TYPES[extname(rel)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
    res.end('Introuvable')
  }
}).listen(PORT, () => console.log(`http://localhost:${PORT}`))
```

- [ ] **Step 6: Écrire `README.md`**

```markdown
# Riftbound Scanner

PWA de scan de cartes Riftbound pour Android, avec export CSV.

## Développement

    npm test              # logique pure, sans navigateur
    npm run dev           # http://localhost:8080
    npm run catalog:update  # rafraîchit data/cards.json depuis l'API Riftcodex

## Tester sur le téléphone

Chrome exige HTTPS pour la caméra. En développement, brancher le téléphone en USB,
ouvrir `chrome://inspect` sur le PC, activer « Port forwarding » du port 8080, puis
ouvrir `http://localhost:8080` **sur le téléphone** : `localhost` est un contexte
sécurisé, la caméra est autorisée.

En production, le site est servi en HTTPS par GitHub Pages.
```

- [ ] **Step 7: Lancer les tests**

Run: `node --test`
Expected: PASS, 2 tests.

- [ ] **Step 8: Commit**

```bash
git add package.json .gitignore README.md scripts/serve.js test/smoke.test.js
git commit -m "chore: squelette du depot et serveur de developpement"
```

---

## Task 2: Normalisation et dédoublonnage du catalogue

**Files:**
- Create: `catalog/normalize.js`, `test/catalog-normalize.test.js`

**Interfaces:**
- Consumes: `test/fixtures/catalog-sample.json`.
- Produces:
  - `normalizeCard(apiCard) -> Card` où `Card = { code, riftcodexId, tcgplayerId, name, set, setLabel, number, rarity, type, domain }` ; `code` est `riftbound_id` en minuscules, `domain` un tableau de chaînes, `tcgplayerId` une chaîne ou `null`.
  - `dedupeByCode(cards) -> { cards: Card[], anomalies: Anomaly[] }` où `Anomaly = { code, reason, names }` et `reason ∈ { 'no-tcgplayer-id', 'unexplained-variants' }`.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/catalog-normalize.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { normalizeCard, dedupeByCode } from '../catalog/normalize.js'

const sample = JSON.parse(
  await readFile(new URL('./fixtures/catalog-sample.json', import.meta.url), 'utf8')
)
const byName = (n) => sample.find((c) => c.name === n)

test('normalizeCard aplatit la fiche API et minuscule le code', () => {
  const card = normalizeCard(byName('Bewitching Spirit'))
  assert.deepEqual(card, {
    code: 'unl-121-219',
    riftcodexId: '69c4407d9288b1e85d94de95',
    tcgplayerId: '685592',
    name: 'Bewitching Spirit',
    set: 'UNL',
    setLabel: 'Unleashed',
    number: 121,
    rarity: 'Common',
    type: 'Unit',
    domain: ['Chaos']
  })
})

test('normalizeCard conserve un tcgplayerId absent comme null', () => {
  const incomplete = sample.filter((c) => c.riftbound_id === 'ven-164-166' && !c.tcgplayer_id)[0]
  assert.equal(normalizeCard(incomplete).tcgplayerId, null)
})

test('dedupeByCode écarte la fiche d’ingestion incomplète', () => {
  const { cards } = dedupeByCode(sample.map(normalizeCard))
  const tombs = cards.filter((c) => c.code === 'ven-164-166')
  assert.equal(tombs.length, 1)
  assert.equal(tombs[0].tcgplayerId, '706097')
})

test('dedupeByCode écarte aussi une fiche incomplète portant un nom différent', () => {
  const { cards } = dedupeByCode(sample.map(normalizeCard))
  const shen = cards.filter((c) => c.code === 'ven-042a-166')
  assert.equal(shen.length, 1)
  assert.equal(shen[0].name, 'Shen, Scourge of Shadows (Alternate Art)')
})

test('dedupeByCode conserve les deux cartes d’un couple Metal', () => {
  const { cards } = dedupeByCode(sample.map(normalizeCard))
  const yasuo = cards.filter((c) => c.code === 'opp-259-298')
  assert.equal(yasuo.length, 2)
  assert.deepEqual(
    yasuo.map((c) => c.name).sort(),
    ['Yasuo - Unforgiven', 'Yasuo - Unforgiven (Metal)']
  )
})

test('dedupeByCode ne signale aucune anomalie sur la fixture', () => {
  const { anomalies } = dedupeByCode(sample.map(normalizeCard))
  assert.deepEqual(anomalies, [])
})

test('dedupeByCode signale un groupe sans aucun tcgplayerId et conserve tout', () => {
  const cards = [
    { code: 'xxx-1-9', name: 'A', tcgplayerId: null },
    { code: 'xxx-1-9', name: 'B', tcgplayerId: null }
  ]
  const { cards: kept, anomalies } = dedupeByCode(cards)
  assert.equal(kept.length, 2)
  assert.deepEqual(anomalies, [{ code: 'xxx-1-9', reason: 'no-tcgplayer-id', names: ['A', 'B'] }])
})

test('dedupeByCode signale une ambiguïté résiduelle qui n’est pas un couple Metal', () => {
  const cards = [
    { code: 'yyy-2-9', name: 'Carte A', tcgplayerId: '1' },
    { code: 'yyy-2-9', name: 'Carte B', tcgplayerId: '2' }
  ]
  const { cards: kept, anomalies } = dedupeByCode(cards)
  assert.equal(kept.length, 2)
  assert.deepEqual(anomalies, [
    { code: 'yyy-2-9', reason: 'unexplained-variants', names: ['Carte A', 'Carte B'] }
  ])
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/catalog-normalize.test.js`
Expected: FAIL — `Cannot find module '../catalog/normalize.js'`.

- [ ] **Step 3: Écrire `catalog/normalize.js`**

```js
// Mise à plat et dédoublonnage des fiches renvoyées par l'API Riftcodex.
// Module pur : aucune entrée-sortie.

/** Réduit une fiche API à ce dont l'application a besoin. */
export function normalizeCard(apiCard) {
  return {
    code: apiCard.riftbound_id.toLowerCase(),
    riftcodexId: apiCard.id,
    tcgplayerId: apiCard.tcgplayer_id ?? null,
    name: apiCard.name,
    set: apiCard.set.set_id,
    setLabel: apiCard.set.label,
    number: apiCard.collector_number,
    rarity: apiCard.classification.rarity,
    type: apiCard.classification.type,
    domain: apiCard.classification.domain ?? []
  }
}

const isMetal = (card) => / \(Metal\)$/.test(card.name)

/**
 * Dans un groupe de cartes partageant un code, les fiches d'ingestion incomplètes
 * se reconnaissent à l'absence de tcgplayerId. Vérifié sur les 131 groupes
 * concernés du catalogue, sans contre-exemple. Ce qui subsiste à plusieurs est une
 * variante physique réelle (aujourd'hui, uniquement des couples Normale/Metal).
 */
export function dedupeByCode(cards) {
  const groups = new Map()
  for (const card of cards) {
    if (!groups.has(card.code)) groups.set(card.code, [])
    groups.get(card.code).push(card)
  }

  const kept = []
  const anomalies = []

  for (const [code, group] of groups) {
    if (group.length === 1) {
      kept.push(group[0])
      continue
    }

    const complete = group.filter((c) => c.tcgplayerId)
    const names = group.map((c) => c.name).sort()

    if (complete.length === 0) {
      anomalies.push({ code, reason: 'no-tcgplayer-id', names })
      kept.push(...group)
      continue
    }

    if (complete.length === 1) {
      kept.push(complete[0])
      continue
    }

    const explained = complete.length === 2 && complete.filter(isMetal).length === 1
    if (!explained) anomalies.push({ code, reason: 'unexplained-variants', names })
    kept.push(...complete)
  }

  return { cards: kept, anomalies }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/catalog-normalize.test.js`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add catalog/normalize.js test/catalog-normalize.test.js
git commit -m "feat(catalog): normaliser et dedoublonner les fiches Riftcodex"
```

---

## Task 3: Récupération du catalogue et garde de santé

**Files:**
- Create: `catalog/update.js`, `test/catalog-update.test.js`
- Create: `data/.gitkeep`

**Interfaces:**
- Consumes: `normalizeCard`, `dedupeByCode` de la Task 2.
- Produces:
  - `fetchAllCards(fetchFn) -> Promise<apiCard[]>` — pagine `?page=N&size=100` jusqu'à `pages`.
  - `healthCheck(next, previous) -> { ok: boolean, problems: string[] }` — `previous` vaut `null` au premier import.
  - CLI : `node catalog/update.js` écrit `data/cards.json`, ou sort en code 1 sans rien écrire.

Le format écrit est `{ "generatedAt": "<ISO>", "count": <n>, "cards": [ ... ] }`.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/catalog-update.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { fetchAllCards, healthCheck } from '../catalog/update.js'

/** Faux fetch servant `pages` pages de `size` fiches numérotées. */
function fakeFetch({ pages, size, total }) {
  const calls = []
  const fn = async (url) => {
    const page = Number(new URL(url).searchParams.get('page'))
    calls.push(page)
    const items = Array.from({ length: size }, (_, i) => ({ n: (page - 1) * size + i }))
    return { ok: true, json: async () => ({ items, total, page, size, pages }) }
  }
  fn.calls = calls
  return fn
}

test('fetchAllCards suit la pagination jusqu’au bout', async () => {
  const fetchFn = fakeFetch({ pages: 3, size: 100, total: 300 })
  const items = await fetchAllCards(fetchFn)
  assert.equal(items.length, 300)
  assert.deepEqual(fetchFn.calls, [1, 2, 3])
})

test('fetchAllCards remonte une réponse HTTP en échec', async () => {
  const fetchFn = async () => ({ ok: false, status: 503 })
  await assert.rejects(() => fetchAllCards(fetchFn), /503/)
})

test('healthCheck accepte un premier import', () => {
  const next = [{ code: 'a-1-9', name: 'A', set: 'A', number: 1, tcgplayerId: '1' }]
  assert.deepEqual(healthCheck(next, null), { ok: true, problems: [] })
})

test('healthCheck refuse une chute de plus de 20 % du nombre de cartes', () => {
  const card = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const previous = Array.from({ length: 100 }, (_, i) => card(i))
  const next = Array.from({ length: 79 }, (_, i) => card(i))
  const result = healthCheck(next, previous)
  assert.equal(result.ok, false)
  assert.match(result.problems[0], /79.*100/)
})

test('healthCheck accepte une baisse inférieure au seuil', () => {
  const card = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const previous = Array.from({ length: 100 }, (_, i) => card(i))
  const next = Array.from({ length: 81 }, (_, i) => card(i))
  assert.equal(healthCheck(next, previous).ok, true)
})

test('healthCheck refuse plus de 1 % de champs obligatoires manquants', () => {
  const ok = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const next = Array.from({ length: 100 }, (_, i) => (i < 2 ? { ...ok(i), name: '' } : ok(i)))
  const result = healthCheck(next, null)
  assert.equal(result.ok, false)
  assert.match(result.problems[0], /champ/)
})

test('healthCheck refuse un catalogue vide', () => {
  assert.equal(healthCheck([], null).ok, false)
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/catalog-update.test.js`
Expected: FAIL — `Cannot find module '../catalog/update.js'`.

- [ ] **Step 3: Écrire `catalog/update.js`**

```js
// Récupère le catalogue Riftbound depuis l'API Riftcodex et écrit data/cards.json.
// La garde de santé protège l'instantané commité contre une réponse dégradée.
import { readFile, writeFile } from 'node:fs/promises'
import { normalizeCard, dedupeByCode } from './normalize.js'

const ENDPOINT = 'https://api.riftcodex.com/cards'
const PAGE_SIZE = 100 // plafond imposé par l'API : size=250 est refusé
const MIN_RATIO = 0.8
const MAX_MISSING_RATIO = 0.01
const REQUIRED = ['code', 'name', 'set', 'number']

/** Parcourt toutes les pages. `fetchFn` est injecté pour rendre la fonction testable. */
export async function fetchAllCards(fetchFn) {
  const items = []
  let page = 1
  let pages = 1
  do {
    const response = await fetchFn(`${ENDPOINT}?page=${page}&size=${PAGE_SIZE}`)
    if (!response.ok) throw new Error(`Riftcodex a répondu ${response.status} à la page ${page}`)
    const body = await response.json()
    items.push(...body.items)
    pages = body.pages
    page += 1
  } while (page <= pages)
  return items
}

/** `previous` vaut null au premier import. */
export function healthCheck(next, previous) {
  const problems = []

  if (next.length === 0) {
    problems.push('Le catalogue récupéré est vide.')
    return { ok: false, problems }
  }

  if (previous && next.length < previous.length * MIN_RATIO) {
    problems.push(
      `Chute anormale du nombre de cartes : ${next.length} contre ${previous.length} auparavant.`
    )
  }

  const missing = next.filter((c) => REQUIRED.some((f) => c[f] === undefined || c[f] === null || c[f] === ''))
  if (missing.length > next.length * MAX_MISSING_RATIO) {
    problems.push(
      `${missing.length} cartes sur ${next.length} ont un champ obligatoire manquant.`
    )
  }

  return { ok: problems.length === 0, problems }
}

async function readPrevious(path) {
  try {
    return JSON.parse(await readFile(path, 'utf8')).cards
  } catch {
    return null
  }
}

async function main() {
  const target = new URL('../data/cards.json', import.meta.url)
  const previous = await readPrevious(target)

  console.log('Récupération du catalogue Riftcodex…')
  const raw = await fetchAllCards(globalThis.fetch)
  const { cards, anomalies } = dedupeByCode(raw.map(normalizeCard))

  console.log(`${raw.length} fiches reçues, ${cards.length} cartes après dédoublonnage.`)
  for (const a of anomalies) {
    console.warn(`  anomalie ${a.reason} sur ${a.code} : ${a.names.join(' / ')}`)
  }

  const { ok, problems } = healthCheck(cards, previous)
  if (!ok) {
    console.error("Instantané NON écrit, le fichier existant est préservé :")
    for (const p of problems) console.error(`  - ${p}`)
    process.exitCode = 1
    return
  }

  const payload = { generatedAt: new Date().toISOString(), count: cards.length, cards }
  await writeFile(target, JSON.stringify(payload, null, 2) + '\n', 'utf8')
  console.log(`data/cards.json écrit : ${cards.length} cartes.`)
}

if (import.meta.url === `file://${process.argv[1]}`.replace(/\\/g, '/')) {
  await main()
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/catalog-update.test.js`
Expected: PASS, 7 tests.

- [ ] **Step 5: Produire l'instantané réel**

Run: `npm run catalog:update`
Expected: la sortie annonce environ `1451 fiches reçues, 1320 cartes après dédoublonnage`, aucune anomalie, et `data/cards.json` est écrit. Si le compte diffère nettement, l'API a bougé : lire les anomalies signalées avant de continuer, ne pas contourner la garde.

- [ ] **Step 6: Commit**

```bash
git add catalog/update.js test/catalog-update.test.js data/cards.json
git commit -m "feat(catalog): recuperer le catalogue avec garde de sante"
```

---

## Task 4: Lecture du code de collection

**Files:**
- Create: `src/recognize/collector-code.js`, `test/collector-code.test.js`

**Interfaces:**
- Consumes: rien.
- Produces:
  - `canonicalCode(code) -> string` — minuscule, zéros de tête du numéro retirés. `'UNL-029a-219' -> 'unl-29a-219'`.
  - `parseCollectorCode(rawOcrText) -> string | null` — renvoie un code **canonique**, ou `null` si le texte ne ressemble pas à un code.

**Limite connue, assumée.** La lettre `b` est à la fois une variante réelle et la
lecture courante du chiffre `8` par l'OCR : `unl-12b-219` peut être la variante b du
numéro 12 ou le numéro 128 mal lu. La variante l'emporte, et le filet de
`candidatesFor` rattrape l'autre cas, qui est à distance d'édition 1.

**Pourquoi des préfixes en dur.** Le catalogue contient des codes à préfixe littéral (`sfd-t03`, `ven-r06`, `ven-sp4-006`). On ne peut pas les détecter par la forme, parce que l'OCR transforme couramment `1` en `i` : « i2i » est le nombre 121, pas un préfixe `i` suivi de 21. La liste des préfixes est donc fermée et vérifiée par un test contre le catalogue réel.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/collector-code.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseCollectorCode, canonicalCode, KNOWN_PREFIXES } from '../src/recognize/collector-code.js'

test('canonicalCode minuscule et retire les zéros de tête du numéro', () => {
  assert.equal(canonicalCode('UNL-029a-219'), 'unl-29a-219')
  assert.equal(canonicalCode('sfd-t03'), 'sfd-t3')
  assert.equal(canonicalCode('unl-121-219'), 'unl-121-219')
})

test('lit un code propre', () => {
  assert.equal(parseCollectorCode('UNL-121-219'), 'unl-121-219')
})

test('accepte les séparateurs rencontrés à l’impression', () => {
  for (const raw of ['UNL 121 219', 'UNL/121/219', 'UNL·121·219', 'UNL – 121 – 219']) {
    assert.equal(parseCollectorCode(raw), 'unl-121-219', raw)
  }
})

test('conserve le suffixe de variante', () => {
  assert.equal(parseCollectorCode('UNL-116a-219'), 'unl-116a-219')
  assert.equal(parseCollectorCode('UNL-229*-219'), 'unl-229*-219')
})

test('corrige les confusions de l’OCR dans le numéro', () => {
  assert.equal(parseCollectorCode('UNL-I2I-2I9'), 'unl-121-219')
  assert.equal(parseCollectorCode('UNL-O29a-2I9'), 'unl-29a-219')
  assert.equal(parseCollectorCode('UNL-S6-219'), 'unl-56-219')
})

test('corrige les confusions de l’OCR dans le code de set', () => {
  assert.equal(parseCollectorCode('0GN-012-219'), 'ogn-12-219')
})

test('accepte les codes à préfixe littéral', () => {
  assert.equal(parseCollectorCode('SFD-T03'), 'sfd-t3')
  assert.equal(parseCollectorCode('VEN-R06'), 'ven-r6')
  // Ici « 006 » est le total du set, pas le numéro : il garde ses zéros.
  assert.equal(parseCollectorCode('VEN-SP4-006'), 'ven-sp4-006')
})

test('accepte un code sans segment de total', () => {
  assert.equal(parseCollectorCode('OGS-007'), 'ogs-7')
})

test('ignore le texte qui entoure le code', () => {
  assert.equal(parseCollectorCode('  UNL-121-219   Jonathan Santoro  '), 'unl-121-219')
})

test('rejette ce qui n’est pas un code', () => {
  for (const raw of ['', '   ', 'Bewitching Spirit', '219', 'Illustration : Wild Blue Studios']) {
    assert.equal(parseCollectorCode(raw), null, raw)
  }
})

test('les préfixes connus couvrent tous les codes du catalogue réel', async () => {
  const url = new URL('../data/cards.json', import.meta.url)
  const { cards } = JSON.parse(await readFile(url, 'utf8'))
  const unmatched = cards.filter((c) => parseCollectorCode(c.code) !== canonicalCode(c.code))
  assert.deepEqual(unmatched.map((c) => c.code), [])
  assert.ok(KNOWN_PREFIXES.length > 0)
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/collector-code.test.js`
Expected: FAIL — `Cannot find module '../src/recognize/collector-code.js'`.

- [ ] **Step 3: Écrire `src/recognize/collector-code.js`**

```js
// Lecture du code de collection imprimé en bas de carte, à partir d'un texte d'OCR.
// Module pur : aucune entrée-sortie.

/**
 * Préfixes littéraux rencontrés dans le catalogue (jetons, promos, cartes spéciales).
 * Liste fermée volontairement : l'OCR confond `1` et `i`, donc « i2i » doit être lu
 * comme le nombre 121 et non comme le préfixe « i » suivi de 21.
 * Le test `collector-code.test.js` vérifie cette liste contre data/cards.json.
 */
export const KNOWN_PREFIXES = ['sp', 't', 'r']

const SEPARATORS = /[\s/·•_.,:;|—–]+/g
const LETTER_TO_DIGIT = { o: '0', i: '1', l: '1', s: '5', b: '8', z: '2' }
const DIGIT_TO_LETTER = { 0: 'o', 1: 'i', 5: 's', 8: 'b' }

const toDigits = (s) => s.replace(/[a-z]/g, (ch) => LETTER_TO_DIGIT[ch] ?? ch)
const toLetters = (s) => s.replace(/[0-9]/g, (ch) => DIGIT_TO_LETTER[ch] ?? ch)
const stripLeadingZeros = (s) => s.replace(/^0+(?=\d)/, '')

/** Forme de référence d'un code : minuscule, sans zéros de tête sur le numéro. */
export function canonicalCode(code) {
  const parts = String(code).toLowerCase().split('-')
  if (parts.length < 2) return String(code).toLowerCase()
  parts[1] = parts[1].replace(/\d+/, (n) => stripLeadingZeros(n))
  return parts.join('-')
}

/** Sépare un segment central en { prefix, number, variant }. */
function splitCore(core) {
  // Seules lettres de variante présentes au catalogue : a (112 cartes), b (8), * (36).
  // La restriction est indispensable : sans elle, « i2i » — lecture courante de 121 —
  // serait lu comme le nombre 12 suivi d'une variante « i ».
  const variantMatch = core.match(/[ab*]$/)
  const variant = variantMatch && core.length > 1 ? variantMatch[0] : ''
  const withoutVariant = variant ? core.slice(0, -1) : core

  const prefix = KNOWN_PREFIXES.find((p) => withoutVariant.startsWith(p)) ?? ''
  const digits = toDigits(withoutVariant.slice(prefix.length))

  if (!/^\d+$/.test(digits)) return null
  return { prefix, number: stripLeadingZeros(digits), variant: variant === '*' ? '*' : variant }
}

export function parseCollectorCode(rawOcrText) {
  if (typeof rawOcrText !== 'string') return null

  const cleaned = rawOcrText
    .toLowerCase()
    .replace(SEPARATORS, '-')
    .replace(/[^a-z0-9*-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')

  // On cherche le code où qu'il soit dans la ligne, artiste et mentions légales compris.
  const shape = /(?:^|-)([a-z0-9]{2,4})-([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:-|$)/
  const found = cleaned.match(shape)
  if (!found) return null

  const set = toLetters(found[1])
  if (!/^[a-z]{2,4}$/.test(set)) return null

  const core = splitCore(found[2])
  if (!core) return null

  const totalRaw = found[3] ? toDigits(found[3]) : null
  const total = totalRaw && /^\d+$/.test(totalRaw) ? totalRaw : null

  const head = `${set}-${core.prefix}${core.number}${core.variant}`
  return total ? `${head}-${total}` : head
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/collector-code.test.js`
Expected: PASS, 11 tests. Le dernier confronte la fonction aux 1320 codes réels — s'il échoue, la liste `KNOWN_PREFIXES` ou la forme de `shape` doit être élargie, pas le test affaibli.

- [ ] **Step 5: Commit**

```bash
git add src/recognize/collector-code.js test/collector-code.test.js
git commit -m "feat(recognize): lire le code de collection depuis un texte d OCR"
```

---

## Task 5: Index et rapprochement

**Files:**
- Create: `src/recognize/match.js`, `test/match.test.js`

**Interfaces:**
- Consumes: `canonicalCode` de la Task 4, le type `Card` de la Task 2.
- Produces:
  - `buildIndex(cards) -> Map<string, Card[]>` — clés canoniques.
  - `matchCards(code, index) -> Card[]` — tableau vide si rien.
  - `candidatesFor(code, index, limit = 3) -> Card[]` — les plus proches à distance d'édition ≤ 2.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/match.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { normalizeCard, dedupeByCode } from '../catalog/normalize.js'
import { buildIndex, matchCards, candidatesFor } from '../src/recognize/match.js'

const sample = JSON.parse(
  await readFile(new URL('./fixtures/catalog-sample.json', import.meta.url), 'utf8')
)
const catalog = dedupeByCode(sample.map(normalizeCard)).cards
const index = buildIndex(catalog)

test('matchCards retrouve une carte unique', () => {
  const found = matchCards('unl-121-219', index)
  assert.equal(found.length, 1)
  assert.equal(found[0].name, 'Bewitching Spirit')
})

test('matchCards retrouve les deux cartes d’un couple Metal', () => {
  assert.equal(matchCards('opp-259-298', index).length, 2)
})

test('matchCards ignore les zéros de tête et la casse', () => {
  assert.equal(matchCards('UNL-029a-219', index).length, 1)
  assert.equal(matchCards('unl-29a-219', index).length, 1)
})

test('matchCards renvoie un tableau vide pour un code inconnu', () => {
  assert.deepEqual(matchCards('zzz-999-999', index), [])
})

test('candidatesFor propose les codes proches', () => {
  const found = candidatesFor('unl-122-219', index)
  assert.ok(found.some((c) => c.name === 'Bewitching Spirit'))
})

test('candidatesFor ne propose rien au-delà de la distance 2', () => {
  assert.deepEqual(candidatesFor('abc-999-111', index), [])
})

test('candidatesFor respecte la limite demandée', () => {
  assert.ok(candidatesFor('unl-122-219', index, 1).length <= 1)
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/match.test.js`
Expected: FAIL — `Cannot find module '../src/recognize/match.js'`.

- [ ] **Step 3: Écrire `src/recognize/match.js`**

```js
// Index du catalogue et rapprochement d'un code lu. Module pur.
import { canonicalCode } from './collector-code.js'

/** Un code peut légitimement désigner plusieurs cartes (couples Normale/Metal). */
export function buildIndex(cards) {
  const index = new Map()
  for (const card of cards) {
    const key = canonicalCode(card.code)
    if (!index.has(key)) index.set(key, [])
    index.get(key).push(card)
  }
  return index
}

export function matchCards(code, index) {
  return index.get(canonicalCode(code)) ?? []
}

function editDistance(a, b) {
  if (a === b) return 0
  if (Math.abs(a.length - b.length) > 2) return 3
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i)
  for (let i = 1; i <= a.length; i += 1) {
    const current = [i]
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      current[j] = Math.min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
    }
    previous = current
  }
  return previous[b.length]
}

/** Les cartes dont le code est le plus proche, pour l'écran de lecture douteuse. */
export function candidatesFor(code, index, limit = 3) {
  const target = canonicalCode(code)
  const scored = []
  for (const key of index.keys()) {
    const distance = editDistance(target, key)
    if (distance > 0 && distance <= 2) scored.push({ key, distance })
  }
  scored.sort((a, b) => a.distance - b.distance || a.key.localeCompare(b.key))
  return scored.slice(0, limit).flatMap(({ key }) => index.get(key))
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/match.test.js`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/recognize/match.js test/match.test.js
git commit -m "feat(recognize): indexer le catalogue et rapprocher un code lu"
```

---

## Task 6: Variantes et finition

**Files:**
- Create: `src/recognize/variant.js`, `test/variant.test.js`

**Interfaces:**
- Consumes: le type `Card` de la Task 2.
- Produces:
  - `variantOf(name) -> string | null` — le suffixe entre parenthèses en fin de nom (`'Metal'`, `'Alternate Art'`, `'Overnumbered'`, `'Signature'`…), sinon `null`.
  - `resolveVariant(cards, { finish }) -> { card: Card } | { ambiguous: Card[] }` où `finish ∈ { 'normal', 'metal' }`.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/variant.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { variantOf, resolveVariant } from '../src/recognize/variant.js'

const normal = { name: 'Yasuo - Unforgiven' }
const metal = { name: 'Yasuo - Unforgiven (Metal)' }

test('variantOf extrait le suffixe de nom', () => {
  assert.equal(variantOf('Yasuo - Unforgiven (Metal)'), 'Metal')
  assert.equal(variantOf('Poppy - Paragon (Alternate Art)'), 'Alternate Art')
  assert.equal(variantOf('Vi - Piltover Enforcer (Signature)'), 'Signature')
})

test('variantOf renvoie null sans suffixe', () => {
  assert.equal(variantOf('Bewitching Spirit'), null)
})

test('variantOf ignore une parenthèse qui n’est pas en fin de nom', () => {
  assert.equal(variantOf('Gold // Buff (jeton) recto'), null)
})

test('resolveVariant laisse passer une carte unique quel que soit le réglage', () => {
  assert.deepEqual(resolveVariant([normal], { finish: 'metal' }), { card: normal })
})

test('resolveVariant choisit la version Metal en mode metal', () => {
  assert.deepEqual(resolveVariant([normal, metal], { finish: 'metal' }), { card: metal })
})

test('resolveVariant choisit la version normale en mode normal', () => {
  assert.deepEqual(resolveVariant([normal, metal], { finish: 'normal' }), { card: normal })
})

test('resolveVariant rend la main quand le réglage ne tranche pas', () => {
  const a = { name: 'Carte A' }
  const b = { name: 'Carte B' }
  assert.deepEqual(resolveVariant([a, b], { finish: 'normal' }), { ambiguous: [a, b] })
})

test('resolveVariant rend la main si le mode metal ne trouve aucune version Metal', () => {
  const a = { name: 'Carte A' }
  const b = { name: 'Carte B' }
  assert.deepEqual(resolveVariant([a, b], { finish: 'metal' }), { ambiguous: [a, b] })
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/variant.test.js`
Expected: FAIL — `Cannot find module '../src/recognize/variant.js'`.

- [ ] **Step 3: Écrire `src/recognize/variant.js`**

```js
// Arbitrage entre les cartes partageant un même code imprimé. Module pur.

/** Le suffixe entre parenthèses en fin de nom, seul marqueur de variante du catalogue. */
export function variantOf(name) {
  const found = String(name).match(/\(([^()]+)\)$/)
  return found ? found[1] : null
}

const isMetal = (card) => variantOf(card.name) === 'Metal'

/**
 * Le réglage de finition de la session tranche les couples Normale/Metal, qui sont
 * aujourd'hui les seuls codes ambigus du catalogue. Toute autre ambiguïté est
 * renvoyée à l'utilisateur plutôt que devinée.
 */
export function resolveVariant(cards, { finish }) {
  if (cards.length === 1) return { card: cards[0] }

  const wanted = finish === 'metal' ? cards.filter(isMetal) : cards.filter((c) => !isMetal(c))
  if (wanted.length === 1) return { card: wanted[0] }

  return { ambiguous: cards }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/variant.test.js`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/recognize/variant.js test/variant.test.js
git commit -m "feat(recognize): arbitrer les variantes par le reglage de finition"
```

---

## Task 7: Détection de langue

**Files:**
- Create: `src/recognize/language.js`, `test/language.test.js`

**Interfaces:**
- Consumes: rien.
- Produces: `detectLanguage(text) -> 'fr' | 'en' | null`. Renvoie `null` quand l'écart entre les deux langues est trop faible pour trancher.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/language.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { detectLanguage } from '../src/recognize/language.js'

test('reconnaît un texte de règles anglais', () => {
  assert.equal(
    detectLanguage('When you play me, choose a player. They discard 1.'),
    'en'
  )
})

test('reconnaît un texte de règles français', () => {
  assert.equal(
    detectLanguage('Quand vous me jouez, choisissez un joueur. Il défausse 1 carte.'),
    'fr'
  )
})

test('tolère l’absence d’accents, que l’OCR perd souvent', () => {
  assert.equal(
    detectLanguage('Quand vous me jouez, choisissez un joueur. Il defausse une carte.'),
    'fr'
  )
})

test('ne tranche pas sur un texte trop court', () => {
  assert.equal(detectLanguage('Vi'), null)
  assert.equal(detectLanguage(''), null)
})

test('ne tranche pas quand les deux langues sont à égalité', () => {
  assert.equal(detectLanguage('a une'), null)
})

test('ignore la casse et la ponctuation', () => {
  assert.equal(detectLanguage('WHEN YOU PLAY ME, CHOOSE A PLAYER!'), 'en')
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/language.test.js`
Expected: FAIL — `Cannot find module '../src/recognize/language.js'`.

- [ ] **Step 3: Écrire `src/recognize/language.js`**

```js
// Détection best-effort de la langue d'impression, par mots-outils du texte de règles.
// Aucune base publique ne fournit les noms traduits : c'est le seul signal disponible.
// Module pur.

const STOPWORDS = {
  en: ['when', 'you', 'the', 'play', 'choose', 'card', 'your', 'may', 'gain', 'discard', 'draw', 'unit', 'and', 'target'],
  fr: ['quand', 'vous', 'une', 'les', 'des', 'joueur', 'carte', 'vos', 'avec', 'puis', 'defausse', 'choisissez', 'unite', 'cible']
}

const MIN_HITS = 2
const MIN_MARGIN = 2

const normalize = (text) =>
  String(text)
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .split(/[^a-z]+/)
    .filter(Boolean)

export function detectLanguage(text) {
  const words = normalize(text)
  if (words.length < 3) return null

  const score = (list) => words.filter((w) => list.includes(w)).length
  const en = score(STOPWORDS.en)
  const fr = score(STOPWORDS.fr)

  if (Math.max(en, fr) < MIN_HITS) return null
  if (Math.abs(en - fr) < MIN_MARGIN) return null
  return en > fr ? 'en' : 'fr'
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/language.test.js`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/recognize/language.js test/language.test.js
git commit -m "feat(recognize): detecter la langue par mots-outils"
```

---

## Task 8: Entrées de collection

**Files:**
- Create: `src/collection/entries.js`, `test/entries.test.js`

**Interfaces:**
- Consumes: le type `Card` de la Task 2, `variantOf` de la Task 6.
- Produces:
  - `entryKey(entry) -> string`
  - `addScan(entries, scan) -> { entries: Entry[], outcome }` où `scan = { card, rawCode, finish, language, condition, scannedAt }` (`card` vaut `null` pour une carte inconnue) et `outcome = { type: 'added', index } | { type: 'duplicate', index }`. En cas de doublon, `entries` est **inchangé** : la décision revient à l'utilisateur.
  - `incrementEntry(entries, index) -> Entry[]`
  - `updateEntry(entries, index, patch) -> Entry[]`
  - `removeEntry(entries, index) -> Entry[]`

`Entry = { code, name, set, setLabel, number, rarity, type, domain, riftcodexId, tcgplayerId, variant, finish, language, condition, quantity, rawCode, scannedAt, unknown }`.

Toutes ces fonctions sont **immuables** : elles renvoient un nouveau tableau.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/entries.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { addScan, incrementEntry, updateEntry, removeEntry, entryKey } from '../src/collection/entries.js'

const card = {
  code: 'unl-121-219',
  riftcodexId: 'abc',
  tcgplayerId: '685592',
  name: 'Bewitching Spirit',
  set: 'UNL',
  setLabel: 'Unleashed',
  number: 121,
  rarity: 'Common',
  type: 'Unit',
  domain: ['Chaos']
}
const scan = {
  card,
  rawCode: 'unl-121-219',
  finish: 'normal',
  language: 'en',
  condition: 'NM',
  scannedAt: '2026-09-27T10:00:00.000Z'
}

test('addScan crée une entrée complète', () => {
  const { entries, outcome } = addScan([], scan)
  assert.deepEqual(outcome, { type: 'added', index: 0 })
  assert.equal(entries.length, 1)
  assert.equal(entries[0].name, 'Bewitching Spirit')
  assert.equal(entries[0].quantity, 1)
  assert.equal(entries[0].unknown, false)
  assert.equal(entries[0].variant, null)
})

test('addScan n’incrémente pas de lui-même un doublon', () => {
  const first = addScan([], scan).entries
  const { entries, outcome } = addScan(first, scan)
  assert.deepEqual(outcome, { type: 'duplicate', index: 0 })
  assert.equal(entries[0].quantity, 1, 'la décision revient à l’utilisateur')
  assert.equal(entries, first, 'le tableau est rendu tel quel')
})

test('addScan distingue les finitions, langues et états', () => {
  let entries = addScan([], scan).entries
  entries = addScan(entries, { ...scan, finish: 'metal' }).entries
  entries = addScan(entries, { ...scan, language: 'fr' }).entries
  entries = addScan(entries, { ...scan, condition: 'EX' }).entries
  assert.equal(entries.length, 4)
})

test('addScan enregistre une carte inconnue sans la perdre', () => {
  const { entries, outcome } = addScan([], { ...scan, card: null, rawCode: 'zzz-999-999' })
  assert.equal(outcome.type, 'added')
  assert.equal(entries[0].unknown, true)
  assert.equal(entries[0].rawCode, 'zzz-999-999')
  assert.equal(entries[0].code, 'zzz-999-999')
  assert.equal(entries[0].name, '')
})

test('addScan retient la variante issue du nom', () => {
  const metal = { ...card, name: 'Yasuo - Unforgiven (Metal)' }
  const { entries } = addScan([], { ...scan, card: metal, finish: 'metal' })
  assert.equal(entries[0].variant, 'Metal')
})

test('entryKey sépare deux cartes inconnues de codes bruts différents', () => {
  const a = { code: 'aaa', unknown: true, finish: 'normal', language: 'en', condition: 'NM' }
  const b = { code: 'bbb', unknown: true, finish: 'normal', language: 'en', condition: 'NM' }
  assert.notEqual(entryKey(a), entryKey(b))
})

test('incrementEntry ajoute un exemplaire sans muter le tableau', () => {
  const before = addScan([], scan).entries
  const after = incrementEntry(before, 0)
  assert.equal(after[0].quantity, 2)
  assert.equal(before[0].quantity, 1)
})

test('updateEntry applique une correction ponctuelle', () => {
  const before = addScan([], scan).entries
  const after = updateEntry(before, 0, { condition: 'EX' })
  assert.equal(after[0].condition, 'EX')
  assert.equal(before[0].condition, 'NM')
})

test('removeEntry retire la ligne visée', () => {
  const before = addScan([], scan).entries
  assert.deepEqual(removeEntry(before, 0), [])
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/entries.test.js`
Expected: FAIL — `Cannot find module '../src/collection/entries.js'`.

- [ ] **Step 3: Écrire `src/collection/entries.js`**

```js
// Construction et mise à jour des lignes de collection. Module pur et immuable.
import { variantOf } from '../recognize/variant.js'

/** Deux exemplaires ne fusionnent que s'ils sont vendables sous la même annonce. */
export function entryKey(entry) {
  return [entry.code, entry.finish, entry.language, entry.condition].join('|')
}

function entryFromScan({ card, rawCode, finish, language, condition, scannedAt }) {
  if (!card) {
    return {
      code: rawCode,
      name: '',
      set: '',
      setLabel: '',
      number: null,
      rarity: '',
      type: '',
      domain: [],
      riftcodexId: null,
      tcgplayerId: null,
      variant: null,
      finish,
      language,
      condition,
      quantity: 1,
      rawCode,
      scannedAt,
      unknown: true
    }
  }

  return {
    code: card.code,
    name: card.name,
    set: card.set,
    setLabel: card.setLabel,
    number: card.number,
    rarity: card.rarity,
    type: card.type,
    domain: card.domain,
    riftcodexId: card.riftcodexId,
    tcgplayerId: card.tcgplayerId,
    variant: variantOf(card.name),
    finish,
    language,
    condition,
    quantity: 1,
    rawCode,
    scannedAt,
    unknown: false
  }
}

/**
 * N'incrémente jamais d'elle-même : un doublon est signalé et l'utilisateur tranche.
 * C'est ce qui évite qu'une carte restée devant l'objectif s'ajoute en boucle.
 */
export function addScan(entries, scan) {
  const entry = entryFromScan(scan)
  const key = entryKey(entry)
  const index = entries.findIndex((e) => entryKey(e) === key)

  if (index !== -1) return { entries, outcome: { type: 'duplicate', index } }
  return { entries: [...entries, entry], outcome: { type: 'added', index: entries.length } }
}

export function incrementEntry(entries, index) {
  return entries.map((e, i) => (i === index ? { ...e, quantity: e.quantity + 1 } : e))
}

export function updateEntry(entries, index, patch) {
  return entries.map((e, i) => (i === index ? { ...e, ...patch } : e))
}

export function removeEntry(entries, index) {
  return entries.filter((_, i) => i !== index)
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/entries.test.js`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/collection/entries.js test/entries.test.js
git commit -m "feat(collection): construire et mettre a jour les lignes de collection"
```

---

## Task 9: Export CSV

**Files:**
- Create: `src/collection/csv.js`, `test/csv.test.js`

**Interfaces:**
- Consumes: le type `Entry` de la Task 8.
- Produces: `toCsv(entries) -> string` — commence par `﻿`, séparateur `;`, fins de ligne `\r\n`.

Colonnes, dans cet ordre :
`quantity;name;set;set_label;collector_number;riftbound_id;variant;rarity;type;domain;finish;language;condition;price;riftcodex_id;tcgplayer_id;raw_code;scanned_at`

- [ ] **Step 1: Écrire les tests qui échouent**

`test/csv.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { toCsv } from '../src/collection/csv.js'

const entry = {
  code: 'unl-121-219',
  name: 'Bewitching Spirit',
  set: 'UNL',
  setLabel: 'Unleashed',
  number: 121,
  rarity: 'Common',
  type: 'Unit',
  domain: ['Chaos'],
  riftcodexId: 'abc',
  tcgplayerId: '685592',
  variant: null,
  finish: 'normal',
  language: 'en',
  condition: 'NM',
  quantity: 2,
  rawCode: 'unl-121-219',
  scannedAt: '2026-09-27T10:00:00.000Z',
  unknown: false
}

const lines = (csv) => csv.replace(/^﻿/, '').trimEnd().split('\r\n')

test('commence par un BOM UTF-8', () => {
  assert.ok(toCsv([]).startsWith('﻿'))
})

test('écrit l’en-tête même sans ligne', () => {
  assert.equal(lines(toCsv([])).length, 1)
  assert.match(lines(toCsv([]))[0], /^quantity;name;set;/)
})

test('sépare les colonnes par des points-virgules', () => {
  const [, row] = lines(toCsv([entry]))
  assert.equal(row.split(';')[0], '2')
  assert.equal(row.split(';')[1], 'Bewitching Spirit')
  assert.equal(row.split(';')[5], 'unl-121-219')
})

test('laisse la colonne price vide', () => {
  const header = lines(toCsv([entry]))[0].split(';')
  const row = lines(toCsv([entry]))[1].split(';')
  assert.equal(row[header.indexOf('price')], '')
})

test('joint les domaines par une barre verticale', () => {
  const multi = { ...entry, domain: ['Fury', 'Order'] }
  const header = lines(toCsv([multi]))[0].split(';')
  const row = lines(toCsv([multi]))[1].split(';')
  assert.equal(row[header.indexOf('domain')], 'Fury|Order')
})

test('protège un nom contenant un point-virgule', () => {
  const tricky = { ...entry, name: 'Gold; Buff' }
  assert.match(lines(toCsv([tricky]))[1], /"Gold; Buff"/)
})

test('double les guillemets internes', () => {
  const tricky = { ...entry, name: 'Le "Boss"' }
  assert.match(lines(toCsv([tricky]))[1], /"Le ""Boss"""/)
})

test('exporte une carte inconnue avec son code brut', () => {
  const unknown = { ...entry, unknown: true, name: '', code: 'zzz-9-9', rawCode: 'zzz-9-9' }
  const header = lines(toCsv([unknown]))[0].split(';')
  const row = lines(toCsv([unknown]))[1].split(';')
  assert.equal(row[header.indexOf('raw_code')], 'zzz-9-9')
})

test('utilise des fins de ligne CRLF', () => {
  assert.ok(toCsv([entry]).includes('\r\n'))
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/csv.test.js`
Expected: FAIL — `Cannot find module '../src/collection/csv.js'`.

- [ ] **Step 3: Écrire `src/collection/csv.js`**

```js
// Sérialisation de la collection en CSV. Module pur.
// Point-virgule et BOM : Excel en configuration française ouvre alors le fichier
// correctement d'un double-clic, sans cesser d'être du CSV standard.

const COLUMNS = [
  ['quantity', (e) => e.quantity],
  ['name', (e) => e.name],
  ['set', (e) => e.set],
  ['set_label', (e) => e.setLabel],
  ['collector_number', (e) => e.number ?? ''],
  ['riftbound_id', (e) => e.code],
  ['variant', (e) => e.variant ?? ''],
  ['rarity', (e) => e.rarity],
  ['type', (e) => e.type],
  ['domain', (e) => (e.domain ?? []).join('|')],
  ['finish', (e) => e.finish],
  ['language', (e) => e.language ?? ''],
  ['condition', (e) => e.condition],
  ['price', () => ''],
  ['riftcodex_id', (e) => e.riftcodexId ?? ''],
  ['tcgplayer_id', (e) => e.tcgplayerId ?? ''],
  ['raw_code', (e) => e.rawCode ?? ''],
  ['scanned_at', (e) => e.scannedAt]
]

const escape = (value) => {
  const text = String(value ?? '')
  return /[";\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

export function toCsv(entries) {
  const header = COLUMNS.map(([name]) => name).join(';')
  const rows = entries.map((e) => COLUMNS.map(([, read]) => escape(read(e))).join(';'))
  return '﻿' + [header, ...rows].join('\r\n') + '\r\n'
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/csv.test.js`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/collection/csv.js test/csv.test.js
git commit -m "feat(collection): exporter la collection en CSV point-virgule"
```

---

## Task 10: Machine d'état du scan

**Files:**
- Create: `src/scan/machine.js`, `test/scan-machine.test.js`

**Interfaces:**
- Consumes: `parseCollectorCode` (Task 4), `matchCards`, `candidatesFor` (Task 5), `resolveVariant` (Task 6), `entryKey` (Task 8).
- Produces: `createScanMachine({ index, confirmFrames = 2 }) -> { onFrame(rawText, context), decide(code, context), reset() }`
  - `context = { finish, language, condition, entries }`
  - `onFrame` renvoie `null` tant qu'il n'y a rien à faire, sinon un des événements :
    - `{ type: 'accept', code, card }`
    - `{ type: 'duplicate', code, card, index }`
    - `{ type: 'ambiguous', code, cards }`
    - `{ type: 'unknown', code, candidates }`
  - `decide(code, context)` rend le même verdict pour un code déjà certain, sans passer
    par la règle des deux trames. C'est ce qu'emploie la saisie manuelle au clavier.

C'est le cœur testable du flux : la règle des deux trames, le refus de réémettre tant que la carte reste devant l'objectif, et l'aiguillage vers les quatre issues.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/scan-machine.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { normalizeCard, dedupeByCode } from '../catalog/normalize.js'
import { buildIndex } from '../src/recognize/match.js'
import { addScan } from '../src/collection/entries.js'
import { createScanMachine } from '../src/scan/machine.js'

const sample = JSON.parse(
  await readFile(new URL('./fixtures/catalog-sample.json', import.meta.url), 'utf8')
)
const index = buildIndex(dedupeByCode(sample.map(normalizeCard)).cards)
const base = { finish: 'normal', language: 'en', condition: 'NM', entries: [] }

/** Rejoue une séquence de textes d'OCR et collecte les événements émis. */
function replay(machine, frames, context = base) {
  return frames.map((f) => machine.onFrame(f, context)).filter(Boolean)
}

test('une seule trame ne suffit pas', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['UNL-121-219']), [])
})

test('deux trames identiques valident la lecture', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219'])
  assert.equal(events.length, 1)
  assert.equal(events[0].type, 'accept')
  assert.equal(events[0].card.name, 'Bewitching Spirit')
})

test('deux lectures différentes ne valident rien', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['UNL-121-219', 'UNL-122-219']), [])
})

test('une carte qui reste devant l’objectif n’est pas réémise', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219', 'UNL-121-219', 'UNL-121-219'])
  assert.equal(events.length, 1)
})

test('retirer puis remontrer la carte réémet un événement', () => {
  const machine = createScanMachine({ index })
  const frames = ['UNL-121-219', 'UNL-121-219', '', '', 'UNL-121-219', 'UNL-121-219']
  assert.equal(replay(machine, frames).length, 2)
})

test('un code déjà en collection remonte un doublon', () => {
  const machine = createScanMachine({ index })
  const card = dedupeByCode(sample.map(normalizeCard)).cards.find((c) => c.code === 'unl-121-219')
  const { entries } = addScan([], {
    card,
    rawCode: card.code,
    finish: 'normal',
    language: 'en',
    condition: 'NM',
    scannedAt: '2026-09-27T10:00:00.000Z'
  })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219'], { ...base, entries })
  assert.equal(events[0].type, 'duplicate')
  assert.equal(events[0].index, 0)
})

test('le réglage de finition tranche un couple Metal sans interrompre le scan', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['OPP-259-298', 'OPP-259-298'], { ...base, finish: 'metal' })
  assert.equal(events[0].type, 'accept')
  assert.equal(events[0].card.name, 'Yasuo - Unforgiven (Metal)')
})

test('le mode normal retient la version sans suffixe du couple Metal', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['OPP-259-298', 'OPP-259-298'])
  assert.equal(events[0].card.name, 'Yasuo - Unforgiven')
})

test('un code inconnu remonte les candidats proches', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-122-219', 'UNL-122-219'])
  assert.equal(events[0].type, 'unknown')
  assert.equal(events[0].code, 'unl-122-219')
  assert.ok(events[0].candidates.some((c) => c.name === 'Bewitching Spirit'))
})

test('un texte illisible ne produit rien', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['Jonathan Santoro', 'Illustration']), [])
})

test('decide tranche immédiatement, sans règle des deux trames', () => {
  const machine = createScanMachine({ index })
  const verdict = machine.decide('unl-121-219', base)
  assert.equal(verdict.type, 'accept')
  assert.equal(verdict.card.name, 'Bewitching Spirit')
})

test('decide n’interfère pas avec la lecture en cours de la caméra', () => {
  const machine = createScanMachine({ index })
  machine.onFrame('UNL-121-219', base)
  machine.decide('opp-259-298', base)
  assert.equal(machine.onFrame('UNL-121-219', base).type, 'accept')
})

test('reset oublie la lecture en cours', () => {
  const machine = createScanMachine({ index })
  machine.onFrame('UNL-121-219', base)
  machine.reset()
  assert.equal(machine.onFrame('UNL-121-219', base), null)
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/scan-machine.test.js`
Expected: FAIL — `Cannot find module '../src/scan/machine.js'`.

- [ ] **Step 3: Écrire `src/scan/machine.js`**

```js
// Aiguillage du flux de scan à partir d'une suite de textes d'OCR. Module pur :
// aucune caméra, aucun DOM. C'est ce qui permet de tester le flux complet en rejouant
// une séquence de trames.
import { parseCollectorCode } from '../recognize/collector-code.js'
import { detectLanguage } from '../recognize/language.js'
import { matchCards, candidatesFor } from '../recognize/match.js'
import { resolveVariant } from '../recognize/variant.js'
import { entryKey } from '../collection/entries.js'

export function createScanMachine({ index, confirmFrames = 2 }) {
  let pending = null // code lu sur les trames récentes
  let streak = 0
  let emitted = null // code déjà signalé, tant que la carte reste devant l'objectif

  function reset() {
    pending = null
    streak = 0
    emitted = null
  }

  function decide(code, { finish, language, condition, entries }) {
    const cards = matchCards(code, index)

    if (cards.length === 0) {
      return { type: 'unknown', code, candidates: candidatesFor(code, index) }
    }

    const resolved = resolveVariant(cards, { finish })
    if (resolved.ambiguous) return { type: 'ambiguous', code, cards: resolved.ambiguous }

    const card = resolved.card
    const key = entryKey({ code: card.code, finish, language, condition })
    const existing = entries.findIndex((e) => entryKey(e) === key)

    if (existing !== -1) return { type: 'duplicate', code, card, index: existing }
    return { type: 'accept', code, card }
  }

  function onFrame(rawText, context) {
    const code = parseCollectorCode(rawText)

    if (!code) {
      // Plus rien de lisible : la carte a quitté le cadre, on réarme.
      pending = null
      streak = 0
      emitted = null
      return null
    }

    if (code !== pending) {
      pending = code
      streak = 1
      return null
    }

    streak += 1
    if (streak < confirmFrames) return null
    if (emitted === code) return null

    emitted = code
    return decide(code, context)
  }

  return { onFrame, decide, reset }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/scan-machine.test.js`
Expected: PASS, 13 tests.

- [ ] **Step 5: Lancer toute la suite**

Run: `node --test`
Expected: PASS, aucune régression.

- [ ] **Step 6: Commit**

```bash
git add src/scan/machine.js test/scan-machine.test.js
git commit -m "feat(scan): machine d etat du flux de scan, testee sans camera"
```

---

## Task 11: Persistance IndexedDB

**Files:**
- Create: `src/collection/store.js`, `test/store.test.js`

**Interfaces:**
- Consumes: le type `Entry` de la Task 8.
- Produces: `createStore({ indexedDB }) -> { load(), save(entries), loadSettings(), saveSettings(settings) }`, toutes asynchrones.

La base s'appelle `riftbound-scanner`, version 1, avec un unique magasin `state` en clé-valeur. Les clés utilisées sont `'entries'` et `'settings'`. `indexedDB` est injecté pour permettre un test avec un faux.

- [ ] **Step 1: Écrire les tests qui échouent**

`test/store.test.js` :

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../src/collection/store.js'

/** Faux IndexedDB minimal : un objet en mémoire suffit à vérifier le contrat. */
function fakeIndexedDB() {
  const data = new Map()
  return {
    data,
    open() {
      const request = {}
      queueMicrotask(() => {
        request.result = {
          transaction: () => ({
            objectStore: () => ({
              get(key) {
                const r = {}
                queueMicrotask(() => {
                  r.result = data.get(key)
                  r.onsuccess?.()
                })
                return r
              },
              put(value, key) {
                const r = {}
                queueMicrotask(() => {
                  data.set(key, value)
                  r.onsuccess?.()
                })
                return r
              }
            })
          }),
          objectStoreNames: { contains: () => true }
        }
        request.onsuccess?.()
      })
      return request
    }
  }
}

test('load renvoie un tableau vide quand rien n’est stocké', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  assert.deepEqual(await store.load(), [])
})

test('save puis load restituent les lignes', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  const entries = [{ code: 'unl-121-219', quantity: 1 }]
  await store.save(entries)
  assert.deepEqual(await store.load(), entries)
})

test('loadSettings renvoie les réglages par défaut', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  assert.deepEqual(await store.loadSettings(), {
    finish: 'normal',
    language: 'en',
    condition: 'NM'
  })
})

test('saveSettings puis loadSettings restituent les réglages', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  await store.saveSettings({ finish: 'metal', language: 'fr', condition: 'EX' })
  assert.deepEqual(await store.loadSettings(), {
    finish: 'metal',
    language: 'fr',
    condition: 'EX'
  })
})

test('sans IndexedDB, le magasin reste utilisable en mémoire', async () => {
  const store = createStore({ indexedDB: null })
  assert.equal(store.persistent, false)
  await store.save([{ code: 'a', quantity: 1 }])
  assert.deepEqual(await store.load(), [{ code: 'a', quantity: 1 }])
})
```

- [ ] **Step 2: Lancer les tests pour les voir échouer**

Run: `node --test test/store.test.js`
Expected: FAIL — `Cannot find module '../src/collection/store.js'`.

- [ ] **Step 3: Écrire `src/collection/store.js`**

```js
// Persistance de la collection et des réglages. Seule couche d'entrées-sorties du
// dossier collection/. `indexedDB` est injecté pour rester testable.

const DB_NAME = 'riftbound-scanner'
const DB_VERSION = 1
const STORE = 'state'
const DEFAULT_SETTINGS = { finish: 'normal', language: 'en', condition: 'NM' }

const promisify = (request) =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })

export function createStore({ indexedDB }) {
  // Repli en mémoire : mode navigation privée, stockage bloqué, tests.
  if (!indexedDB) {
    const memory = new Map()
    return {
      persistent: false,
      async load() {
        return memory.get('entries') ?? []
      },
      async save(entries) {
        memory.set('entries', entries)
      },
      async loadSettings() {
        return memory.get('settings') ?? { ...DEFAULT_SETTINGS }
      },
      async saveSettings(settings) {
        memory.set('settings', settings)
      }
    }
  }

  let db = null

  async function open() {
    if (db) return db
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const result = request.result
      if (!result.objectStoreNames.contains(STORE)) result.createObjectStore(STORE)
    }
    db = await promisify(request)
    return db
  }

  async function read(key, fallback) {
    const connection = await open()
    const store = connection.transaction(STORE, 'readonly').objectStore(STORE)
    const value = await promisify(store.get(key))
    return value ?? fallback
  }

  async function write(key, value) {
    const connection = await open()
    const store = connection.transaction(STORE, 'readwrite').objectStore(STORE)
    await promisify(store.put(value, key))
  }

  return {
    persistent: true,
    load: () => read('entries', []),
    save: (entries) => write('entries', entries),
    loadSettings: () => read('settings', { ...DEFAULT_SETTINGS }),
    saveSettings: (settings) => write('settings', settings)
  }
}
```

- [ ] **Step 4: Lancer les tests**

Run: `node --test test/store.test.js`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/collection/store.js test/store.test.js
git commit -m "feat(collection): persister la collection en IndexedDB"
```

---

## Task 12: Caméra et OCR

**Files:**
- Create: `src/scan/camera.js`, `src/scan/ocr.js`

**Interfaces:**
- Consumes: rien des tâches précédentes.
- Produces:
  - `startCamera(videoElement) -> Promise<{ stop() }>` — lève une erreur nommée `CameraError` avec un message en français si l'accès échoue.
  - `grabViewfinder(videoElement, canvas, band) -> void` — dessine la bande du viseur sur le canvas. `band = { top, height }` en fractions de la hauteur de la vidéo.
  - `createTesseractOcr() -> Promise<{ read(canvas) -> Promise<string>, terminate() }>`

Ces deux modules sont de pures entrées-sorties : ils ne sont pas couverts par des tests automatisés, c'est la passe manuelle de la Task 15 qui les valide. Tout ce qui est décidable a déjà été testé dans la Task 10.

- [ ] **Step 1: Écrire `src/scan/camera.js`**

```js
// Accès caméra et découpe de la zone du viseur. Entrées-sorties pures.

export class CameraError extends Error {
  constructor(message) {
    super(message)
    this.name = 'CameraError'
  }
}

export async function startCamera(videoElement) {
  if (!globalThis.isSecureContext) {
    throw new CameraError(
      "La caméra exige une connexion sécurisée. Ouvre la page en HTTPS, ou via le " +
        'port forwarding de chrome://inspect en développement.'
    )
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new CameraError("Ce navigateur n'expose pas la caméra.")
  }

  let stream
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 } },
      audio: false
    })
  } catch (cause) {
    if (cause.name === 'NotAllowedError') {
      throw new CameraError("L'accès à la caméra a été refusé.")
    }
    throw new CameraError(`La caméra n'a pas pu démarrer : ${cause.message}`)
  }

  videoElement.srcObject = stream
  await videoElement.play()

  return {
    stop() {
      for (const track of stream.getTracks()) track.stop()
      videoElement.srcObject = null
    }
  }
}

/**
 * Ne recopie que la bande du viseur. Réduire la surface analysée est ce qui rend
 * l'OCR tenable sur téléphone : environ 5 % de l'image au lieu de la totalité.
 */
export function grabViewfinder(videoElement, canvas, band) {
  const width = videoElement.videoWidth
  const height = videoElement.videoHeight
  if (!width || !height) return

  const sourceY = Math.round(height * band.top)
  const sourceHeight = Math.round(height * band.height)

  canvas.width = width
  canvas.height = sourceHeight

  const context = canvas.getContext('2d', { willReadFrequently: true })
  context.drawImage(videoElement, 0, sourceY, width, sourceHeight, 0, 0, width, sourceHeight)
}

/** Recopie l'image entière. Employée uniquement par la détection de langue, ponctuelle. */
export function grabFull(videoElement, canvas) {
  const width = videoElement.videoWidth
  const height = videoElement.videoHeight
  if (!width || !height) return
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d', { willReadFrequently: true }).drawImage(videoElement, 0, 0)
}
```

- [ ] **Step 2: Écrire `src/scan/ocr.js`**

```js
// Adaptateur Tesseract.js. Entrées-sorties pures : la machine d'état ne connaît que
// le texte renvoyé par read(), ce qui permet de la tester avec un faux moteur.

const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js'

// Alphabet volontairement réduit au strict nécessaire du code de collection :
// c'est le second levier de fiabilité, après la réduction de la zone analysée.
const WHITELIST = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/*'

// Modes de l'unique worker : le code en bas de carte, ou le texte de règles complet.
const MODE_CODE = { tessedit_char_whitelist: WHITELIST, tessedit_pageseg_mode: '7' }
const MODE_TEXTE = { tessedit_char_whitelist: '', tessedit_pageseg_mode: '3' }

async function loadTesseract() {
  if (globalThis.Tesseract) return globalThis.Tesseract
  await new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = TESSERACT_URL
    script.onload = resolve
    script.onerror = () => reject(new Error("Tesseract.js n'a pas pu être chargé."))
    document.head.append(script)
  })
  return globalThis.Tesseract
}

export async function createTesseractOcr() {
  const Tesseract = await loadTesseract()
  const worker = await Tesseract.createWorker('eng')
  await worker.setParameters(MODE_CODE)
  let mode = 'code'

  async function useMode(next) {
    if (mode === next) return
    await worker.setParameters(next === 'code' ? MODE_CODE : MODE_TEXTE)
    mode = next
  }

  return {
    /** Lecture du code : alphabet réduit, une seule ligne. C'est le chemin chaud. */
    async read(canvas) {
      await useMode('code')
      const { data } = await worker.recognize(canvas)
      return data.text ?? ''
    },
    /** Lecture du texte de règles, alphabet complet. Ponctuelle et coûteuse. */
    async readText(canvas) {
      await useMode('texte')
      const { data } = await worker.recognize(canvas)
      await useMode('code')
      return data.text ?? ''
    },
    terminate: () => worker.terminate()
  }
}
```

- [ ] **Step 3: Vérifier que rien n'est cassé**

Run: `node --test`
Expected: PASS. Ces deux modules ne sont pas importés par les tests ; la suite doit rester verte.

- [ ] **Step 4: Commit**

```bash
git add src/scan/camera.js src/scan/ocr.js
git commit -m "feat(scan): adaptateurs camera et OCR Tesseract"
```

---

## Task 13: Coquille PWA et écran de scan

**Files:**
- Create: `index.html`, `styles.css`, `src/app.js`, `src/ui/scan-view.js`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces: `createScanView({ root, machine, store, getState, setState }) -> { start(), stop() }`.

L'écran de scan occupe la page : flux vidéo, viseur en surimpression, barre de réglages collants, et une zone de dialogue qui se remplit pour les événements `duplicate`, `ambiguous` et `unknown`.

- [ ] **Step 1: Écrire `index.html`**

```html
<!doctype html>
<html lang="fr">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    <title>Riftbound Scanner</title>
    <link rel="manifest" href="manifest.webmanifest" />
    <link rel="stylesheet" href="styles.css" />
  </head>
  <body>
    <header class="barre">
      <button id="onglet-scan" class="onglet actif" type="button">Scanner</button>
      <button id="onglet-liste" class="onglet" type="button">Collection <span id="compteur">0</span></button>
    </header>

    <main>
      <section id="vue-scan" class="vue">
        <div class="reglages">
          <button id="reglage-finition" type="button" aria-pressed="false">Normale</button>
          <button id="reglage-langue" type="button">EN</button>
          <button id="detecter-langue" type="button">Détecter la langue</button>
          <span id="etat-scan" role="status" aria-live="polite"></span>
        </div>
        <div class="camera">
          <video id="flux" playsinline muted></video>
          <div id="viseur" class="viseur"></div>
        </div>
        <canvas id="tampon" hidden></canvas>
        <form id="saisie-manuelle">
          <label for="code-manuel">Saisir un code</label>
          <input id="code-manuel" name="code" placeholder="UNL-121-219" autocomplete="off" />
          <button type="submit">Ajouter</button>
        </form>
        <div id="dialogue" class="dialogue" hidden></div>
      </section>

      <section id="vue-liste" class="vue" hidden></section>
    </main>

    <script type="module" src="src/app.js"></script>
  </body>
</html>
```

- [ ] **Step 2: Écrire `styles.css`**

Le viseur affiché doit rester aligné sur la constante `BAND` de `scan-view.js`
(`top: 78%`, `height: 10%`), sinon l'utilisateur vise une zone qui n'est pas celle
qui est analysée.

```css
:root {
  --fond: #101014;
  --texte: #e8e8ee;
  --accent: #6ea8fe;
  --bord: #2a2a35;
  color-scheme: dark;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--fond);
  color: var(--texte);
  font: 16px/1.4 system-ui, sans-serif;
}

button, select, input {
  min-height: 44px;
  font: inherit;
  color: inherit;
  background: #1b1b24;
  border: 1px solid var(--bord);
  border-radius: 8px;
  padding: 0 12px;
}

button { cursor: pointer; }
button:disabled { opacity: 0.5; cursor: default; }

.barre {
  display: flex;
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--fond);
  border-bottom: 1px solid var(--bord);
}

.onglet {
  flex: 1;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.onglet.actif { box-shadow: inset 0 -2px 0 var(--accent); }

.reglages {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 8px;
}

.reglages [role='status'] { flex: 1 1 100%; font-size: 14px; opacity: 0.8; }

#reglage-finition[aria-pressed='true'] { border-color: var(--accent); color: var(--accent); }

.camera { position: relative; background: #000; aspect-ratio: 3 / 4; }

.camera video { width: 100%; height: 100%; object-fit: cover; display: block; }

/* Aligné sur BAND dans src/ui/scan-view.js. Modifier les deux ensemble. */
.viseur {
  position: absolute;
  left: 4%;
  right: 4%;
  top: 78%;
  height: 10%;
  border: 2px solid var(--accent);
  border-radius: 6px;
  pointer-events: none;
}

#saisie-manuelle { display: flex; gap: 8px; padding: 8px; align-items: center; }
#saisie-manuelle label { font-size: 14px; opacity: 0.8; }
#saisie-manuelle input { flex: 1; min-width: 0; }

.dialogue {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  background: #16161f;
  border-top: 1px solid var(--accent);
}

.dialogue p { margin: 0 0 4px; }

table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { padding: 8px 6px; text-align: left; border-bottom: 1px solid var(--bord); }
tr.inconnue { color: #ffb86b; }
```

- [ ] **Step 3: Écrire `src/ui/scan-view.js`**

```js
// Écran de scan : caméra, boucle d'analyse, réglages collants, écrans de décision.
import { startCamera, grabViewfinder, grabFull, CameraError } from '../scan/camera.js'
import { createTesseractOcr } from '../scan/ocr.js'
import { addScan, incrementEntry } from '../collection/entries.js'
import { parseCollectorCode } from '../recognize/collector-code.js'
import { detectLanguage } from '../recognize/language.js'

const BAND = { top: 0.78, height: 0.1 } // bande basse, là où le code est imprimé
const INTERVAL = 400

export function createScanView({ root, machine, getState, setState, onStatus }) {
  const video = root.querySelector('#flux')
  const canvas = root.querySelector('#tampon')
  const dialogue = root.querySelector('#dialogue')
  const boutonFinition = root.querySelector('#reglage-finition')
  const boutonLangue = root.querySelector('#reglage-langue')

  let camera = null
  let ocr = null
  let timer = null
  let busy = false

  const beep = () => {
    const context = new AudioContext()
    const oscillator = context.createOscillator()
    oscillator.frequency.value = 880
    oscillator.connect(context.destination)
    oscillator.start()
    oscillator.stop(context.currentTime + 0.06)
  }

  function commit(card, rawCode) {
    const { entries, settings } = getState()
    const { entries: next } = addScan(entries, {
      card,
      rawCode,
      finish: settings.finish,
      language: settings.language,
      condition: settings.condition,
      scannedAt: new Date().toISOString()
    })
    setState({ entries: next })
    beep()
  }

  function closeDialogue() {
    dialogue.hidden = true
    dialogue.replaceChildren()
    machine.reset()
  }

  function ask(titre, boutons) {
    dialogue.replaceChildren()
    const h = document.createElement('p')
    h.textContent = titre
    dialogue.append(h)
    for (const [label, action] of boutons) {
      const bouton = document.createElement('button')
      bouton.type = 'button'
      bouton.textContent = label
      bouton.addEventListener('click', () => {
        action()
        closeDialogue()
      })
      dialogue.append(bouton)
    }
    dialogue.hidden = false
  }

  function handle(event) {
    if (!event) return

    if (event.type === 'accept') {
      commit(event.card, event.code)
      onStatus(`${event.card.name} ajoutée`)
      return
    }

    if (event.type === 'duplicate') {
      const { entries } = getState()
      const quantity = entries[event.index].quantity
      ask(`${event.card.name} — déjà scannée ×${quantity}. Ajouter un exemplaire ?`, [
        ['Ajouter', () => setState({ entries: incrementEntry(getState().entries, event.index) })],
        ['Ignorer', () => {}]
      ])
      return
    }

    if (event.type === 'ambiguous') {
      ask(
        'Ce code correspond à plusieurs cartes. Laquelle ?',
        event.cards.map((card) => [card.name, () => commit(card, event.code)])
      )
      return
    }

    if (event.type === 'unknown') {
      const choix = event.candidates.map((card) => [card.name, () => commit(card, event.code)])
      ask(`Code ${event.code} absent du catalogue.`, [
        ...choix,
        ['Conserver tel quel', () => commit(null, event.code)],
        ['Ignorer', () => {}]
      ])
    }
  }

  async function tick() {
    if (busy || !dialogue.hidden) return
    busy = true
    try {
      grabViewfinder(video, canvas, BAND)
      const texte = await ocr.read(canvas)
      const { entries, settings } = getState()
      handle(machine.onFrame(texte, { ...settings, entries }))
    } finally {
      busy = false
    }
  }

  boutonFinition.addEventListener('click', () => {
    const { settings } = getState()
    const finish = settings.finish === 'normal' ? 'metal' : 'normal'
    setState({ settings: { ...settings, finish } })
    boutonFinition.textContent = finish === 'metal' ? 'Metal' : 'Normale'
    boutonFinition.setAttribute('aria-pressed', String(finish === 'metal'))
  })

  boutonLangue.addEventListener('click', () => {
    const { settings } = getState()
    const language = settings.language === 'en' ? 'fr' : 'en'
    setState({ settings: { ...settings, language } })
    boutonLangue.textContent = language.toUpperCase()
  })

  // Détection de langue : une passe d'OCR à alphabet complet sur la carte entière.
  // Trop coûteuse pour tourner à chaque trame, elle se déclenche à la demande, une
  // fois par paquet, et ne fait que positionner le réglage de session.
  root.querySelector('#detecter-langue').addEventListener('click', async () => {
    if (!ocr) {
      onStatus('La reconnaissance n’est pas encore prête.')
      return
    }
    onStatus('Lecture du texte de la carte…')
    grabFull(video, canvas)
    const language = detectLanguage(await ocr.readText(canvas))
    if (!language) {
      onStatus('Langue indéterminée. Règle-la à la main.')
      return
    }
    const { settings } = getState()
    setState({ settings: { ...settings, language } })
    boutonLangue.textContent = language.toUpperCase()
    onStatus(`Langue détectée : ${language.toUpperCase()}.`)
  })

  root.querySelector('#saisie-manuelle').addEventListener('submit', (e) => {
    e.preventDefault()
    const champ = root.querySelector('#code-manuel')
    const code = parseCollectorCode(champ.value)
    if (!code) {
      onStatus('Code non reconnu.')
      return
    }
    const { entries, settings } = getState()
    handle(machine.decide(code, { ...settings, entries }))
    champ.value = ''
  })

  return {
    async start() {
      try {
        camera = await startCamera(video)
      } catch (error) {
        if (error instanceof CameraError) {
          onStatus(`${error.message} La saisie manuelle reste disponible.`)
          return
        }
        throw error
      }
      onStatus('Chargement de la reconnaissance…')
      ocr = await createTesseractOcr()
      onStatus('Prêt. Vise le code en bas de la carte.')
      timer = setInterval(tick, INTERVAL)
    },
    stop() {
      clearInterval(timer)
      camera?.stop()
      ocr?.terminate()
      camera = null
      ocr = null
    }
  }
}
```

- [ ] **Step 4: Écrire `src/app.js`**

```js
// Câblage de l'application : état central, chargement du catalogue, bascule d'onglets.
import { buildIndex } from './recognize/match.js'
import { createScanMachine } from './scan/machine.js'
import { createStore } from './collection/store.js'
import { createScanView } from './ui/scan-view.js'
import { createListView } from './ui/list-view.js'

const store = createStore({ indexedDB: globalThis.indexedDB ?? null })
const etat = { entries: [], settings: { finish: 'normal', language: 'en', condition: 'NM' } }

const getState = () => etat
const abonnes = []

function setState(patch) {
  Object.assign(etat, patch)
  if (patch.entries) store.save(etat.entries)
  if (patch.settings) store.saveSettings(etat.settings)
  for (const notifier of abonnes) notifier(etat)
}

const status = document.querySelector('#etat-scan')
const onStatus = (message) => {
  status.textContent = message
}

const { cards } = await (await fetch('data/cards.json')).json()
const index = buildIndex(cards)
const machine = createScanMachine({ index })

etat.entries = await store.load()
etat.settings = await store.loadSettings()
if (!store.persistent) {
  onStatus('Stockage indisponible : la collection sera perdue à la fermeture.')
}

const scanView = createScanView({
  root: document.querySelector('#vue-scan'),
  machine,
  getState,
  setState,
  onStatus
})
const listView = createListView({
  root: document.querySelector('#vue-liste'),
  getState,
  setState
})
abonnes.push(listView.render)
abonnes.push(({ entries }) => {
  document.querySelector('#compteur').textContent = entries.reduce((n, e) => n + e.quantity, 0)
})

function afficher(onglet) {
  const scan = onglet === 'scan'
  document.querySelector('#vue-scan').hidden = !scan
  document.querySelector('#vue-liste').hidden = scan
  document.querySelector('#onglet-scan').classList.toggle('actif', scan)
  document.querySelector('#onglet-liste').classList.toggle('actif', !scan)
  if (scan) scanView.start()
  else scanView.stop()
}

document.querySelector('#onglet-scan').addEventListener('click', () => afficher('scan'))
document.querySelector('#onglet-liste').addEventListener('click', () => afficher('liste'))

listView.render(etat)
afficher('scan')

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js')
```

- [ ] **Step 5: Vérifier dans un navigateur de bureau**

Run: `npm run dev` puis ouvrir `http://localhost:8080`.
Expected: la page s'affiche, les onglets basculent, la caméra du portable démarre ou affiche un message clair. La saisie manuelle de `UNL-121-219` ajoute la carte et le compteur passe à 1.

- [ ] **Step 6: Commit**

```bash
git add index.html styles.css src/app.js src/ui/scan-view.js
git commit -m "feat(ui): ecran de scan avec reglages collants et ecrans de decision"
```

---

## Task 14: Liste, export et installation PWA

**Files:**
- Create: `src/ui/list-view.js`, `manifest.webmanifest`, `sw.js`, `icon-192.png`, `icon-512.png`

**Interfaces:**
- Consumes: `toCsv` (Task 9), `updateEntry`, `removeEntry`, `incrementEntry` (Task 8).
- Produces: `createListView({ root, getState, setState }) -> { render(state) }`.

- [ ] **Step 1: Écrire `src/ui/list-view.js`**

```js
// Liste de la collection : consultation, correction ponctuelle, export CSV.
import { toCsv } from '../collection/csv.js'
import { updateEntry, removeEntry, incrementEntry } from '../collection/entries.js'

const CONDITIONS = ['NM', 'EX', 'GD', 'LP', 'PL', 'PO']
const LANGUES = ['en', 'fr']

export function createListView({ root, getState, setState }) {
  function telecharger() {
    const csv = toCsv(getState().entries)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const lien = document.createElement('a')
    lien.href = URL.createObjectURL(blob)
    lien.download = `riftbound-${new Date().toISOString().slice(0, 10)}.csv`
    lien.click()
    URL.revokeObjectURL(lien.href)
  }

  function selecteur(valeurs, courante, onChange) {
    const select = document.createElement('select')
    for (const valeur of valeurs) {
      const option = document.createElement('option')
      option.value = valeur
      option.textContent = valeur.toUpperCase()
      option.selected = valeur === courante
      select.append(option)
    }
    select.addEventListener('change', () => onChange(select.value))
    return select
  }

  function ligne(entry, i) {
    const tr = document.createElement('tr')
    if (entry.unknown) tr.classList.add('inconnue')

    const nom = document.createElement('td')
    nom.textContent = entry.unknown ? `Inconnue (${entry.rawCode})` : entry.name
    const code = document.createElement('td')
    code.textContent = entry.code
    const set = document.createElement('td')
    set.textContent = entry.set
    const finition = document.createElement('td')
    finition.textContent = entry.finish === 'metal' ? 'Metal' : 'Normale'

    const quantite = document.createElement('td')
    quantite.textContent = entry.quantity
    const plus = document.createElement('button')
    plus.type = 'button'
    plus.textContent = '+'
    plus.addEventListener('click', () => setState({ entries: incrementEntry(getState().entries, i) }))
    quantite.append(plus)

    const langue = document.createElement('td')
    langue.append(
      selecteur(LANGUES, entry.language, (v) =>
        setState({ entries: updateEntry(getState().entries, i, { language: v }) })
      )
    )

    const etat = document.createElement('td')
    etat.append(
      selecteur(CONDITIONS, entry.condition, (v) =>
        setState({ entries: updateEntry(getState().entries, i, { condition: v }) })
      )
    )

    const actions = document.createElement('td')
    const supprimer = document.createElement('button')
    supprimer.type = 'button'
    supprimer.textContent = 'Supprimer'
    supprimer.addEventListener('click', () =>
      setState({ entries: removeEntry(getState().entries, i) })
    )
    actions.append(supprimer)

    tr.append(nom, code, set, finition, quantite, langue, etat, actions)
    return tr
  }

  function render({ entries }) {
    root.replaceChildren()

    const barre = document.createElement('div')
    barre.className = 'reglages'
    const total = document.createElement('span')
    const exemplaires = entries.reduce((n, e) => n + e.quantity, 0)
    total.textContent = `${entries.length} lignes, ${exemplaires} cartes`
    const bouton = document.createElement('button')
    bouton.type = 'button'
    bouton.textContent = 'Télécharger le CSV'
    bouton.disabled = entries.length === 0
    bouton.addEventListener('click', telecharger)
    barre.append(total, bouton)
    root.append(barre)

    if (entries.length === 0) {
      const vide = document.createElement('p')
      vide.textContent = 'Aucune carte scannée pour le moment.'
      root.append(vide)
      return
    }

    const table = document.createElement('table')
    const thead = document.createElement('thead')
    const entetes = ['Nom', 'Code', 'Set', 'Finition', 'Qté', 'Langue', 'État', '']
    const tr = document.createElement('tr')
    for (const texte of entetes) {
      const th = document.createElement('th')
      th.textContent = texte
      tr.append(th)
    }
    thead.append(tr)
    const tbody = document.createElement('tbody')
    entries.forEach((entry, i) => tbody.append(ligne(entry, i)))
    table.append(thead, tbody)
    root.append(table)
  }

  return { render }
}
```

- [ ] **Step 2: Écrire `manifest.webmanifest`**

```json
{
  "name": "Riftbound Scanner",
  "short_name": "Riftbound",
  "start_url": ".",
  "display": "standalone",
  "orientation": "portrait",
  "background_color": "#101014",
  "theme_color": "#101014",
  "icons": [
    { "src": "icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "icon-512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
```

- [ ] **Step 3: Créer les deux icônes**

Écrire `scripts/make-icons.js`, qui produit deux PNG unis sans aucune dépendance —
`node:zlib` suffit à encoder un PNG valide.

```js
// Génère icon-192.png et icon-512.png : carrés unis à la couleur du thème.
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'

const COLOR = [0x10, 0x10, 0x14]

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

function crc32(buffer) {
  let c = 0xffffffff
  for (const byte of buffer) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

function png(size) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // profondeur
  ihdr[9] = 2 // couleur vraie RVB
  const row = Buffer.concat([
    Buffer.from([0]), // filtre « none »
    Buffer.concat(Array.from({ length: size }, () => Buffer.from(COLOR)))
  ])
  const raw = Buffer.concat(Array.from({ length: size }, () => row))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0))
  ])
}

for (const size of [192, 512]) {
  const name = `icon-${size}.png`
  writeFileSync(new URL(`../${name}`, import.meta.url), png(size))
  console.log(`${name} écrit`)
}
```

Run: `node scripts/make-icons.js`
Expected: `icon-192.png` et `icon-512.png` sont écrits à la racine et s'ouvrent dans une
visionneuse d'images.

- [ ] **Step 4: Écrire `sw.js`**

```js
// Cache d'exécution : la coquille et le catalogue sont pré-chargés, le WASM de
// Tesseract est mis en cache au premier passage réussi. La toute première ouverture
// exige donc du réseau ; les suivantes fonctionnent hors-ligne.
const CACHE = 'riftbound-v1'
const SHELL = [
  '.',
  'index.html',
  'styles.css',
  'manifest.webmanifest',
  'data/cards.json',
  'src/app.js',
  'src/ui/scan-view.js',
  'src/ui/list-view.js',
  'src/scan/machine.js',
  'src/scan/camera.js',
  'src/scan/ocr.js',
  'src/recognize/collector-code.js',
  'src/recognize/match.js',
  'src/recognize/variant.js',
  'src/recognize/language.js',
  'src/collection/entries.js',
  'src/collection/csv.js',
  'src/collection/store.js'
]

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((noms) => Promise.all(noms.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return
  event.respondWith(
    caches.match(event.request).then(
      (hit) =>
        hit ??
        fetch(event.request).then((response) => {
          if (response.ok) {
            const copie = response.clone()
            caches.open(CACHE).then((cache) => cache.put(event.request, copie))
          }
          return response
        })
    )
  )
})
```

- [ ] **Step 5: Vérifier le parcours complet en local**

Run: `npm run dev`, puis dans le navigateur : saisir manuellement `UNL-121-219`, `OPP-259-298` et `ZZZ-999-999`.
Expected: trois lignes apparaissent dans l'onglet Collection, dont une marquée inconnue ; changer l'état d'une ligne le conserve après rechargement de la page ; le bouton télécharge un CSV qui s'ouvre correctement dans un tableur, accents compris.

- [ ] **Step 6: Lancer toute la suite**

Run: `node --test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/ui/list-view.js manifest.webmanifest sw.js scripts/make-icons.js icon-192.png icon-512.png
git commit -m "feat(ui): liste de collection, export CSV et installation PWA"
```

---

## Task 15: Mise en ligne et réglage sur cartes réelles

**Files:**
- Modify: `README.md`
- Create: `.github/workflows/pages.yml`

**Interfaces:**
- Consumes: l'application complète.
- Produces: un site HTTPS et des valeurs de `BAND` et `INTERVAL` validées sur de vraies cartes.

- [ ] **Step 1: Écrire le workflow GitHub Pages**

`.github/workflows/pages.yml` :

```yaml
name: Pages
on:
  push:
    branches: [main]
permissions:
  contents: read
  pages: write
  id-token: write
concurrency:
  group: pages
  cancel-in-progress: true
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - run: node --test
  deploy:
    needs: test
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: .
      - id: deployment
        uses: actions/deploy-pages@v4
```

Le site n'a pas d'étape de compilation : le dépôt est publié tel quel, tests verts exigés.

- [ ] **Step 2: Publier**

```bash
git add .github/workflows/pages.yml
git commit -m "ci: publier le site sur GitHub Pages apres les tests"
git push
```

Puis, dans les réglages du dépôt sur GitHub, section Pages, choisir la source « GitHub Actions ».

- [ ] **Step 3: Installer sur le téléphone**

Ouvrir l'URL Pages dans Chrome Android, autoriser la caméra, puis menu ⋮ → « Ajouter à l'écran d'accueil ».

- [ ] **Step 4: Régler le viseur sur de vraies cartes**

Scanner une dizaine de cartes, dont au moins une Metal et une carte très brillante.

Si le code n'est jamais lu : ajuster `BAND` dans `src/ui/scan-view.js` — `top` monte ou descend la bande, `height` l'élargit. Le viseur affiché en CSS doit rester aligné sur ces valeurs, sinon l'utilisateur vise une zone qui n'est pas celle analysée.

Si la lecture est correcte mais lente : augmenter `INTERVAL`. Si des cartes sont manquées au passage : le diminuer.

Si des faux positifs apparaissent : passer `confirmFrames` à 3 à la construction de la machine dans `src/app.js`.

- [ ] **Step 5: Consigner les réglages retenus**

Ajouter au `README.md` une section « Réglages » indiquant les valeurs finales de `BAND`, `INTERVAL` et `confirmFrames`, et ce qui a été observé sur les cartes brillantes. C'est ce qui évitera de refaire l'expérience à l'aveugle plus tard.

- [ ] **Step 6: Commit**

```bash
git add README.md src/ui/scan-view.js src/app.js
git commit -m "chore(scan): regler le viseur et la cadence sur cartes reelles"
```

---

## Ce que ce plan ne couvre pas

Conformément à la spec : pas de prix, pas de compte, pas de synchronisation entre appareils, pas de reconnaissance d'illustration, pas de publication sur Cardmarket. La transformation du CSV vers le format attendu par les extensions Cardmarket fera l'objet d'une spec et d'un plan distincts.
