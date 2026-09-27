// Récupère le catalogue Riftbound depuis l'API Riftcodex et écrit data/cards.json.
// La garde de santé protège l'instantané commité contre une réponse dégradée.
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { normalizeCard, dedupeByCode } from './normalize.js'

const ENDPOINT = 'https://api.riftcodex.com/cards'
const PAGE_SIZE = 100 // plafond imposé par l'API : size=250 est refusé
const MIN_RATIO = 0.8
const MAX_MISSING_RATIO = 0.01
const REQUIRED = ['code', 'name', 'set', 'number']
// Le catalogue tient aujourd'hui en 15 pages ; cette borne est un filet de
// sécurité très large qui protège d'un `pages` corrompu ou d'une API dégradée.
const MAX_PAGES = 50

/**
 * Parcourt toutes les pages. `fetchFn` est injecté pour rendre la fonction testable.
 *
 * Le nombre total de pages annoncé par l'API est figé sur la première réponse :
 * toute réponse ultérieure qui annonce une valeur différente fait échouer la
 * récupération avec une erreur explicite plutôt que de suivre une cible mouvante.
 * Un plafond dur (`MAX_PAGES`) complète cette protection pour les cas où la
 * valeur annoncée ne change pas mais reste absurde.
 */
export async function fetchAllCards(fetchFn) {
  const items = []
  let page = 1
  let pages = null
  do {
    if (page > MAX_PAGES) {
      throw new Error(
        `Riftcodex annonce plus de ${MAX_PAGES} pages, ce qui dépasse largement le catalogue connu : arrêt par sécurité.`
      )
    }
    const response = await fetchFn(`${ENDPOINT}?page=${page}&size=${PAGE_SIZE}`)
    if (!response.ok) throw new Error(`Riftcodex a répondu ${response.status} à la page ${page}`)
    const body = await response.json()
    items.push(...body.items)
    if (pages === null) {
      pages = body.pages
    } else if (body.pages !== pages) {
      throw new Error(
        `Le nombre de pages annoncé par Riftcodex a changé en cours de parcours (${pages} puis ${body.pages} à la page ${page}) : arrêt par sécurité.`
      )
    }
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

// Comparaison de chemins plutôt que de chaînes file:// : plus robuste sous Windows
// (lettre de lecteur, casse, séparateurs) que la construction d'URL du brief.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await main()
}
