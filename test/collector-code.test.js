import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseCollectorCode, canonicalCode, KNOWN_PREFIXES } from '../src/recognize/collector-code.js'

test('canonicalCode minuscule et retire les zéros de tête du numéro', () => {
  assert.equal(canonicalCode('UNL-029a-219'), 'unl-29a-219')
  assert.equal(canonicalCode('sfd-t03'), 'sfd-t3')
  assert.equal(canonicalCode('unl-121-219'), 'unl-121-219')
})

test('canonicalCode rejette les non-strings', () => {
  assert.equal(canonicalCode(null), null)
  assert.equal(canonicalCode(undefined), null)
  assert.equal(canonicalCode({}), null)
})

test('lit un code propre', () => {
  assert.equal(parseCollectorCode('UNL-121-219'), 'unl-121-219')
})

test('accepte les séparateurs rencontrés a l impression', () => {
  for (const raw of ['UNL 121 219', 'UNL/121/219', 'UNL·121·219', 'UNL – 121 – 219']) {
    assert.equal(parseCollectorCode(raw), 'unl-121-219', raw)
  }
})

test('conserve le suffixe de variante', () => {
  assert.equal(parseCollectorCode('UNL-116a-219'), 'unl-116a-219')
  assert.equal(parseCollectorCode('UNL-229*-219'), 'unl-229*-219')
})

test('corrige les confusions de l OCR dans le numéro', () => {
  assert.equal(parseCollectorCode('UNL-I2I-2I9'), 'unl-121-219')
  assert.equal(parseCollectorCode('UNL-O29a-2I9'), 'unl-29a-219')
  assert.equal(parseCollectorCode('UNL-S6-219'), 'unl-56-219')
})

test('corrige les confusions de l OCR dans le code de set', () => {
  assert.equal(parseCollectorCode('0GN-012-219'), 'ogn-12-219')
})

test('accepte les codes a préfixe littéral', () => {
  assert.equal(parseCollectorCode('SFD-T03'), 'sfd-t3')
  assert.equal(parseCollectorCode('VEN-R06'), 'ven-r6')
  // Ici « 006 » est le total du set, pas le numéro : il garde ses zéros.
  assert.equal(parseCollectorCode('VEN-SP4-006'), 'ven-sp4-006')
})

test('accepte un code sans segment de total', () => {
  assert.equal(parseCollectorCode('OGS-007'), 'ogs-7')
})

test('ignore le texte qui entoure le code', () => {
  assert.equal(parseCollectorCode('  UNL-121-219   Jonathan Santoro  '), 'unl-121-219')
})

test('rejette ce qui n est pas un code', () => {
  for (const raw of ['', '   ', 'Bewitching Spirit', '219', 'Illustration : Wild Blue Studios']) {
    assert.equal(parseCollectorCode(raw), null, raw)
  }
})

test('rejette les correspondances parasites et retourne le vrai code', () => {
  // Les patterns -2/-2 ou -1/-1 sont courants sur une carte (mécanique de jeu),
  // mais ne sont pas des codes de collection. Le vrai code se trouve après.
  assert.equal(parseCollectorCode('Deal -2/-2 to a unit. UNL-121-219'), 'unl-121-219')
  assert.equal(parseCollectorCode('Give -1/-1 until end of turn. UNL-121-219'), 'unl-121-219')
  assert.equal(parseCollectorCode('ab-12 UNL-121-219'), 'unl-121-219')
  assert.equal(parseCollectorCode('v1-1 UNL-121-219'), 'unl-121-219')
})

test('rejette un texte qui ne contient aucun vrai code', () => {
  // Sans un code valide après, retourner null
  assert.equal(parseCollectorCode('Deal -2/-2 to a unit.'), null)
})

test('les préfixes connus couvrent tous les codes du catalogue réel', async () => {
  const url = new URL('../data/cards.json', import.meta.url)
  const { cards } = JSON.parse(await readFile(url, 'utf8'))
  const unmatched = cards.filter((c) => parseCollectorCode(c.code) !== canonicalCode(c.code))
  assert.deepEqual(unmatched.map((c) => c.code), [])
  assert.ok(KNOWN_PREFIXES.length > 0)
})
