import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { normalizeCard, dedupeByCode } from '../catalog/normalize.js'
import { buildIndex } from '../src/recognize/match.js'
import { addScan } from '../src/collection/entries.js'
import { createScanMachine } from '../src/scan/machine.js'

const sample = JSON.parse(
  await readFile(new URL('./fixtures/catalog-sample.json', import.meta.url), 'utf8')
)
const index = buildIndex(dedupeByCode(sample.map(normalizeCard)).cards)
const base = { finish: 'normal', language: 'en', condition: 'NM', entries: [] }

/** Rejoue une séquence de textes d'OCR et collecte les événements émis. */
function replay(machine, frames, context = base) {
  return frames.map((f) => machine.onFrame(f, context)).filter(Boolean)
}

test('une seule trame ne suffit pas', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['UNL-121-219']), [])
})

test('deux trames identiques valident la lecture', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219'])
  assert.equal(events.length, 1)
  assert.equal(events[0].type, 'accept')
  assert.equal(events[0].card.name, 'Bewitching Spirit')
})

test('deux lectures différentes ne valident rien', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['UNL-121-219', 'UNL-122-219']), [])
})

test('une carte qui reste devant l’objectif n’est pas réémise', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219', 'UNL-121-219', 'UNL-121-219'])
  assert.equal(events.length, 1)
})

test('retirer puis remontrer la carte réémet un événement', () => {
  const machine = createScanMachine({ index })
  const frames = ['UNL-121-219', 'UNL-121-219', '', '', 'UNL-121-219', 'UNL-121-219']
  assert.equal(replay(machine, frames).length, 2)
})

test('un code déjà en collection remonte un doublon', () => {
  const machine = createScanMachine({ index })
  const card = dedupeByCode(sample.map(normalizeCard)).cards.find((c) => c.code === 'unl-121-219')
  const { entries } = addScan([], {
    card,
    rawCode: card.code,
    finish: 'normal',
    language: 'en',
    condition: 'NM',
    scannedAt: '2026-09-27T10:00:00.000Z'
  })
  const events = replay(machine, ['UNL-121-219', 'UNL-121-219'], { ...base, entries })
  assert.equal(events[0].type, 'duplicate')
  assert.equal(events[0].index, 0)
})

test('le réglage de finition tranche un couple Metal sans interrompre le scan', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['OPP-259-298', 'OPP-259-298'], { ...base, finish: 'metal' })
  assert.equal(events[0].type, 'accept')
  assert.equal(events[0].card.name, 'Yasuo - Unforgiven (Metal)')
})

test('le mode normal retient la version sans suffixe du couple Metal', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['OPP-259-298', 'OPP-259-298'])
  assert.equal(events[0].card.name, 'Yasuo - Unforgiven')
})

test('un code inconnu remonte les candidats proches', () => {
  const machine = createScanMachine({ index })
  const events = replay(machine, ['UNL-122-219', 'UNL-122-219'])
  assert.equal(events[0].type, 'unknown')
  assert.equal(events[0].code, 'unl-122-219')
  assert.ok(events[0].candidates.some((c) => c.name === 'Bewitching Spirit'))
})

test('un texte illisible ne produit rien', () => {
  const machine = createScanMachine({ index })
  assert.deepEqual(replay(machine, ['Jonathan Santoro', 'Illustration']), [])
})

test('decide tranche immédiatement, sans règle des deux trames', () => {
  const machine = createScanMachine({ index })
  const verdict = machine.decide('unl-121-219', base)
  assert.equal(verdict.type, 'accept')
  assert.equal(verdict.card.name, 'Bewitching Spirit')
})

test('decide n’interfère pas avec la lecture en cours de la caméra', () => {
  const machine = createScanMachine({ index })
  machine.onFrame('UNL-121-219', base)
  machine.decide('opp-259-298', base)
  assert.equal(machine.onFrame('UNL-121-219', base).type, 'accept')
})

test('reset oublie la lecture en cours', () => {
  const machine = createScanMachine({ index })
  machine.onFrame('UNL-121-219', base)
  machine.reset()
  assert.equal(machine.onFrame('UNL-121-219', base), null)
})
