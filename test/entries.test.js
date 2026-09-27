import test from 'node:test'
import assert from 'node:assert/strict'
import { addScan, incrementEntry, updateEntry, removeEntry, entryKey } from '../src/collection/entries.js'

const card = {
  code: 'unl-121-219',
  riftcodexId: 'abc',
  tcgplayerId: '685592',
  name: 'Bewitching Spirit',
  set: 'UNL',
  setLabel: 'Unleashed',
  number: 121,
  rarity: 'Common',
  type: 'Unit',
  domain: ['Chaos']
}
const scan = {
  card,
  rawCode: 'unl-121-219',
  finish: 'normal',
  language: 'en',
  condition: 'NM',
  scannedAt: '2026-09-27T10:00:00.000Z'
}

test('addScan crée une entrée complète', () => {
  const { entries, outcome } = addScan([], scan)
  assert.deepEqual(outcome, { type: 'added', index: 0 })
  assert.equal(entries.length, 1)
  assert.equal(entries[0].name, 'Bewitching Spirit')
  assert.equal(entries[0].quantity, 1)
  assert.equal(entries[0].unknown, false)
  assert.equal(entries[0].variant, null)
})

test('addScan n’incrémente pas de lui-même un doublon', () => {
  const first = addScan([], scan).entries
  const { entries, outcome } = addScan(first, scan)
  assert.deepEqual(outcome, { type: 'duplicate', index: 0 })
  assert.equal(entries[0].quantity, 1, 'la décision revient à l’utilisateur')
  assert.equal(entries, first, 'le tableau est rendu tel quel')
})

test('addScan distingue les finitions, langues et états', () => {
  let entries = addScan([], scan).entries
  entries = addScan(entries, { ...scan, finish: 'metal' }).entries
  entries = addScan(entries, { ...scan, language: 'fr' }).entries
  entries = addScan(entries, { ...scan, condition: 'EX' }).entries
  assert.equal(entries.length, 4)
})

test('addScan enregistre une carte inconnue sans la perdre', () => {
  const { entries, outcome } = addScan([], { ...scan, card: null, rawCode: 'zzz-999-999' })
  assert.equal(outcome.type, 'added')
  assert.equal(entries[0].unknown, true)
  assert.equal(entries[0].rawCode, 'zzz-999-999')
  assert.equal(entries[0].code, 'zzz-999-999')
  assert.equal(entries[0].name, '')
})

test('addScan retient la variante issue du nom', () => {
  const metal = { ...card, name: 'Yasuo - Unforgiven (Metal)' }
  const { entries } = addScan([], { ...scan, card: metal, finish: 'metal' })
  assert.equal(entries[0].variant, 'Metal')
})

test('entryKey sépare deux cartes inconnues de codes bruts différents', () => {
  const a = { code: 'aaa', unknown: true, finish: 'normal', language: 'en', condition: 'NM' }
  const b = { code: 'bbb', unknown: true, finish: 'normal', language: 'en', condition: 'NM' }
  assert.notEqual(entryKey(a), entryKey(b))
})

test('incrementEntry ajoute un exemplaire sans muter le tableau', () => {
  const before = addScan([], scan).entries
  const after = incrementEntry(before, 0)
  assert.equal(after[0].quantity, 2)
  assert.equal(before[0].quantity, 1)
})

test('updateEntry applique une correction ponctuelle', () => {
  const before = addScan([], scan).entries
  const after = updateEntry(before, 0, { condition: 'EX' })
  assert.equal(after[0].condition, 'EX')
  assert.equal(before[0].condition, 'NM')
})

test('removeEntry retire la ligne visée', () => {
  const before = addScan([], scan).entries
  assert.deepEqual(removeEntry(before, 0), [])
})

test('addScan copie le tableau domain sans le partager avec le catalogue', () => {
  const { entries } = addScan([], scan)
  entries[0].domain.push('CORROMPU')
  assert.deepEqual(card.domain, ['Chaos'], 'muter une ligne ne doit pas corrompre le catalogue')
})

test('une carte normale et sa version Metal, memes code et finition, restent deux lignes distinctes', () => {
  const metalCard = { ...card, riftcodexId: 'abc-metal', tcgplayerId: '685593', name: 'Bewitching Spirit (Metal)' }
  const first = addScan([], scan).entries
  const { entries, outcome } = addScan(first, { ...scan, card: metalCard })
  assert.equal(outcome.type, 'added', 'la version Metal ne doit pas etre prise pour un doublon de la normale')
  assert.equal(entries.length, 2)
  assert.equal(entries[1].variant, 'Metal')
})

test('deux exemplaires strictement identiques fusionnent toujours en doublon', () => {
  const sameCard = { ...card }
  const first = addScan([], scan).entries
  const { outcome } = addScan(first, { ...scan, card: sameCard })
  assert.deepEqual(outcome, { type: 'duplicate', index: 0 })
})
