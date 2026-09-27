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
export function resolveVariant(cards, { finish } = {}) {
  if (!['normal', 'metal'].includes(finish)) {
    throw new Error(`finish doit etre 'normal' ou 'metal', recu: ${JSON.stringify(finish)}`)
  }

  if (cards.length === 1) return { card: cards[0] }

  const wanted = finish === 'metal' ? cards.filter(isMetal) : cards.filter((c) => !isMetal(c))
  if (wanted.length === 1) return { card: wanted[0] }

  return { ambiguous: cards }
}
