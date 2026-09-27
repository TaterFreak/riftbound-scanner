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
