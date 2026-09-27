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

test('dedupeByCode écarte la fiche d\'ingestion incomplète', () => {
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

test('dedupeByCode conserve les deux cartes d\'un couple Metal', () => {
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

test('dedupeByCode signale une ambiguïté résiduelle qui n\'est pas un couple Metal', () => {
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

test('dedupeByCode: groupe de 3+ lignes avec 1 incomplète + Normale + Metal', () => {
  const cards = [
    { code: 'zzz-3-9', name: 'Incomplete Card', tcgplayerId: null },
    { code: 'zzz-3-9', name: 'Normal Card', tcgplayerId: '100' },
    { code: 'zzz-3-9', name: 'Metal Card (Metal)', tcgplayerId: '101' }
  ]
  const { cards: kept, anomalies } = dedupeByCode(cards)
  assert.equal(kept.length, 2)
  assert.deepEqual(
    kept.map((c) => c.name).sort(),
    ['Metal Card (Metal)', 'Normal Card']
  )
  assert.deepEqual(anomalies, [])
})

test('dedupeByCode: groupe avec deux Metal complètes signale une anomalie', () => {
  const cards = [
    { code: 'aaa-4-9', name: 'Metal One (Metal)', tcgplayerId: '200' },
    { code: 'aaa-4-9', name: 'Metal Two (Metal)', tcgplayerId: '201' }
  ]
  const { cards: kept, anomalies } = dedupeByCode(cards)
  assert.equal(kept.length, 2)
  assert.deepEqual(anomalies, [
    { code: 'aaa-4-9', reason: 'unexplained-variants', names: ['Metal One (Metal)', 'Metal Two (Metal)'] }
  ])
})

test('dedupeByCode: Metal sans tcgplayerId est écartée silencieusement (caractérisation)', () => {
  // COMPORTEMENT CONNU ET ASSUMÉ : une variante physique réelle (Metal) pas encore
  // référencée sur TCGplayer disparaîtrait silencieusement. Cet angle mort est une
  // conséquence directe de la règle « écarter les incomplètes, garder une seule
  // complète par groupe ». Toute évolution future doit reconsidérer sciemment ce cas.
  const cards = [
    { code: 'bbb-5-9', name: 'Normal Card', tcgplayerId: '300' },
    { code: 'bbb-5-9', name: 'Metal Card (Metal)', tcgplayerId: null }
  ]
  const { cards: kept, anomalies } = dedupeByCode(cards)
  assert.equal(kept.length, 1)
  assert.equal(kept[0].name, 'Normal Card')
  assert.deepEqual(anomalies, [])
})
