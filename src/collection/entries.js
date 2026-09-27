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
