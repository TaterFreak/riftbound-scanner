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
  // Même dernier segment (200) pour tous : cible tst-100-200, 6 candidats à distance 1
  const syntheticCards = [
    { code: 'tst-000-200', riftcodexId: 'a', tcgplayerId: 't1', name: 'A', set: 'T', setLabel: 'T', number: 0, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-010-200', riftcodexId: 'b', tcgplayerId: 't2', name: 'B', set: 'T', setLabel: 'T', number: 10, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-020-200', riftcodexId: 'c', tcgplayerId: 't3', name: 'C', set: 'T', setLabel: 'T', number: 20, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-030-200', riftcodexId: 'd', tcgplayerId: 't4', name: 'D', set: 'T', setLabel: 'T', number: 30, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-040-200', riftcodexId: 'e', tcgplayerId: 't5', name: 'E', set: 'T', setLabel: 'T', number: 40, rarity: 'C', type: 'U', domain: [] },
    { code: 'tst-050-200', riftcodexId: 'f', tcgplayerId: 't6', name: 'F', set: 'T', setLabel: 'T', number: 50, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const found = candidatesFor('tst-100-200', syntheticIndex)
  assert.ok(found.length > 0, 'Au moins un candidat')
  assert.ok(found.length <= 5, `Expected at most 5 results, got ${found.length}`)
})

test('candidatesFor: deplacement de limite explicite fonctionne avec nouvelle limite par defaut', () => {
  // 6 candidats à distance 1 du segment du milieu, même dernier segment
  const syntheticCards = [
    { code: 'lim-000-200', riftcodexId: 'a', tcgplayerId: 't1', name: 'A', set: 'L', setLabel: 'L', number: 0, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-010-200', riftcodexId: 'b', tcgplayerId: 't2', name: 'B', set: 'L', setLabel: 'L', number: 10, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-020-200', riftcodexId: 'c', tcgplayerId: 't3', name: 'C', set: 'L', setLabel: 'L', number: 20, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-030-200', riftcodexId: 'd', tcgplayerId: 't4', name: 'D', set: 'L', setLabel: 'L', number: 30, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-040-200', riftcodexId: 'e', tcgplayerId: 't5', name: 'E', set: 'L', setLabel: 'L', number: 40, rarity: 'C', type: 'U', domain: [] },
    { code: 'lim-100-200', riftcodexId: 'f', tcgplayerId: 't6', name: 'F', set: 'L', setLabel: 'L', number: 100, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const limit1 = candidatesFor('lim-100-200', syntheticIndex, 1)
  const limit5 = candidatesFor('lim-100-200', syntheticIndex, 5)
  assert.equal(limit1.length, 1, 'limit=1 doit retourner exactement 1 resultat')
  assert.ok(limit5.length > 1 && limit5.length <= 5, 'limit=5 doit retourner 2-5 resultats')
})

test('candidatesFor: ordre du resultat favorise la proximite numerique (cas unl-122-219)', () => {
  // Reproduit le cas réel : cible unl-122-219 avec voisins à distance 1, même dernier segment
  const syntheticCards = [
    { code: 'unl-012-219', riftcodexId: 'a', tcgplayerId: 't1', name: '012', set: 'U', setLabel: 'Unl', number: 12, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-102-219', riftcodexId: 'b', tcgplayerId: 't2', name: '102', set: 'U', setLabel: 'Unl', number: 102, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-112-219', riftcodexId: 'c', tcgplayerId: 't3', name: '112', set: 'U', setLabel: 'Unl', number: 112, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-120-219', riftcodexId: 'd', tcgplayerId: 't4', name: '120', set: 'U', setLabel: 'Unl', number: 120, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-121-219', riftcodexId: 'e', tcgplayerId: 't5', name: '121', set: 'U', setLabel: 'Unl', number: 121, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)
  const found = candidatesFor('unl-122-219', syntheticIndex, 5)

  assert.ok(found.length > 0, 'Au moins un candidat doit etre present')

  // Vérifier que 121 (plus proche de 122) est en première position
  assert.equal(found[0].number, 121, `Le premier doit etre 121 (proche de 122), pas ${found[0].number}`)

  // Vérifier l'ordre complet : 121, 120, 112, 102, 012 (tri numérique)
  const expectedOrder = [121, 120, 112, 102, 12]
  const foundNumbers = found.map(c => c.number)
  assert.deepEqual(foundNumbers, expectedOrder, `Ordre attendu ${expectedOrder}, recu ${foundNumbers}`)
})

test('candidatesFor: extraction du numero fonctionne avec préfixes et suffixes', () => {
  // Test les formes spéciales : sfd-t3 (préfixe), unl-116a-219 (suffixe)
  const syntheticCards = [
    { code: 'sfd-t1-50', riftcodexId: 'a', tcgplayerId: 't1', name: 'T1', set: 'S', setLabel: 'Sfd', number: 1, rarity: 'C', type: 'U', domain: [] },
    { code: 'sfd-t4-50', riftcodexId: 'b', tcgplayerId: 't4', name: 'T4', set: 'S', setLabel: 'Sfd', number: 4, rarity: 'C', type: 'U', domain: [] },
    { code: 'sfd-t5-50', riftcodexId: 'x', tcgplayerId: 't5', name: 'T5', set: 'S', setLabel: 'Sfd', number: 5, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-114a-219', riftcodexId: 'd', tcgplayerId: 't4', name: '114a', set: 'U', setLabel: 'Unl', number: 114, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-116a-219', riftcodexId: 'e', tcgplayerId: 't5', name: '116a', set: 'U', setLabel: 'Unl', number: 116, rarity: 'C', type: 'U', domain: [] },
    { code: 'unl-118a-219', riftcodexId: 'f', tcgplayerId: 't6', name: '118a', set: 'U', setLabel: 'Unl', number: 118, rarity: 'C', type: 'U', domain: [] }
  ]
  const syntheticIndex = buildIndex(syntheticCards)

  // Cible sfd-t5-50 : candidats sfd-t1 et sfd-t4 à distance 1
  // sfd-t1 : numéro 1, distance numérique |1-5|=4 de la cible
  // sfd-t4 : numéro 4, distance numérique |4-5|=1 de la cible (plus proche)
  const foundT = candidatesFor('sfd-t5-50', syntheticIndex, 2)
  assert.ok(foundT.length > 0, 'Candidats pour sfd-t5-50')
  // Doit favoriser t4 (numériquement plus proche) sur t1
  assert.equal(foundT[0].number, 4, `Pour sfd-t5-50, le premier doit etre 4 (proche), pas ${foundT[0].number}`)

  // Cible unl-116a-219 : candidats 114a, 118a à distance 1 (116a est exact donc rejeté)
  // 114a : numéro 114, distance numérique |114-116|=2
  // 118a : numéro 118, distance numérique |118-116|=2 (égalité, tri alphabétique fallback)
  const foundA = candidatesFor('unl-116a-219', syntheticIndex, 2)
  assert.ok(foundA.length > 0, 'Candidats pour unl-116a-219')
  const numbers = foundA.map(c => c.number)
  // À distance numérique égale, tri alphabétique : unl-114a < unl-118a
  assert.deepEqual(numbers, [114, 118], `Ordre attendu [114, 118], recu ${numbers}`)
})
