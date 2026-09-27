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
