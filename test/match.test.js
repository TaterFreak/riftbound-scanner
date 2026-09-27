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

test('matchCards retrouve les deux cartes d\'un couple Metal', () => {
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

test('candidatesFor ne propose rien au-dela de la distance 2', () => {
  assert.deepEqual(candidatesFor('abc-999-111', index), [])
})

test('candidatesFor respecte la limite demandee', () => {
  assert.ok(candidatesFor('unl-122-219', index, 1).length <= 1)
})

test('candidatesFor: limite par defaut est 5 et est respectee', () => {
  const syntheticCards = [
    { code: 'tst-000-100', riftcodexId: 'a', tcgplayerId: 't1', name: 'A', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-010-100', riftcodexId: 'b', tcgplayerId: 't2', name: 'B', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-020-100', riftcodexId: 'c', tcgplayerId: 't3', name: 'C', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-030-100', riftcodexId: 'd', tcgplayerId: 't4', name: 'D', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-040-100', riftcodexId: 'e', tcgplayerId: 't5', name: 'E', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-050-100', riftcodexId: 'f', tcgplayerId: 't6', name: 'F', set: 'T', setLabel: 'T', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-100-099', riftcodexId: 'g', tcgplayerId: 't7', name: 'G', set: 'T', setLabel: 'T', number: 99, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-100-101', riftcodexId: 'h', tcgplayerId: 't8', name: 'H', set: 'T', setLabel: 'T', number: 101, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const found = candidatesFor('tst-100-100', syntheticIndex)
  assert.ok(found.length <= 5, `Expected at most 5 results, got ${found.length}`)
})

test('candidatesFor: deplacement de limite explicite fonctionne avec nouvelle limite par defaut', () => {
  const syntheticCards = [
    { code: 'lim-000-100', riftcodexId: 'a', tcgplayerId: 't1', name: 'A', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-010-100', riftcodexId: 'b', tcgplayerId: 't2', name: 'B', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-020-100', riftcodexId: 'c', tcgplayerId: 't3', name: 'C', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-030-100', riftcodexId: 'd', tcgplayerId: 't4', name: 'D', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-040-100', riftcodexId: 'e', tcgplayerId: 't5', name: 'E', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-100-100', riftcodexId: 'f', tcgplayerId: 't6', name: 'F', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const limit1 = candidatesFor('lim-100-100', syntheticIndex, 1)
  const limit5 = candidatesFor('lim-100-100', syntheticIndex, 5)
  assert.equal(limit1.length, 1, 'limit=1 doit retourner exactement 1 resultat')
  assert.ok(limit5.length > 1 && limit5.length <= 5, 'limit=5 doit retourner 2-5 resultats')
})

test('candidatesFor: ordre du resultat favorise la proximite numerique', () => {
  const syntheticCards = [
    { code: 'ppp-100-100', riftcodexId: 'a', tcgplayerId: 't1', name: 'Far', set: 'P', setLabel: 'Ppp', number: 100, rarity: 'C', type: 'U', domain: [] },
    { code: 'ppp-200-500', riftcodexId: 'b', tcgplayerId: 't2', name: 'Close', set: 'P', setLabel: 'Ppp', number: 500, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const found = candidatesFor('ppp-100-500', syntheticIndex, 2)
  assert.ok(found.length > 0, 'Au moins un candidat doit etre present')
  const firstNumber = found[0].number
  assert.equal(firstNumber, 500, `Le premier candidat doit etre numeriquement proche (500), pas ${firstNumber}`)
})
