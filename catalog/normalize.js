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
