import test from 'node:test'
import assert from 'node:assert/strict'
import { variantOf, resolveVariant } from '../src/recognize/variant.js'

const normal = { name: 'Yasuo - Unforgiven' }
const metal = { name: 'Yasuo - Unforgiven (Metal)' }

test('variantOf extrait le suffixe de nom', () => {
  assert.equal(variantOf('Yasuo - Unforgiven (Metal)'), 'Metal')
  assert.equal(variantOf('Poppy - Paragon (Alternate Art)'), 'Alternate Art')
  assert.equal(variantOf('Vi - Piltover Enforcer (Signature)'), 'Signature')
})

test('variantOf renvoie null sans suffixe', () => {
  assert.equal(variantOf('Bewitching Spirit'), null)
})

test('variantOf ignore une parenthese qui n\'est pas en fin de nom', () => {
  assert.equal(variantOf('Gold // Buff (jeton) recto'), null)
})

test('resolveVariant laisse passer une carte unique quel que soit le reglage', () => {
  assert.deepEqual(resolveVariant([normal], { finish: 'metal' }), { card: normal })
})

test('resolveVariant choisit la version Metal en mode metal', () => {
  assert.deepEqual(resolveVariant([normal, metal], { finish: 'metal' }), { card: metal })
})

test('resolveVariant choisit la version normale en mode normal', () => {
  assert.deepEqual(resolveVariant([normal, metal], { finish: 'normal' }), { card: normal })
})

test('resolveVariant rend la main quand le reglage ne tranche pas', () => {
  const a = { name: 'Carte A' }
  const b = { name: 'Carte B' }
  assert.deepEqual(resolveVariant([a, b], { finish: 'normal' }), { ambiguous: [a, b] })
})

test('resolveVariant rend la main si le mode metal ne trouve aucune version Metal', () => {
  const a = { name: 'Carte A' }
  const b = { name: 'Carte B' }
  assert.deepEqual(resolveVariant([a, b], { finish: 'metal' }), { ambiguous: [a, b] })
})
