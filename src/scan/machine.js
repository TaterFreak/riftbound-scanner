// Aiguillage du flux de scan à partir d'une suite de textes d'OCR. Module pur :
// aucune caméra, aucun DOM. C'est ce qui permet de tester le flux complet en rejouant
// une séquence de trames.
import { parseCollectorCode } from '../recognize/collector-code.js'
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
