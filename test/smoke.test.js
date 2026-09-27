import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('le dépôt est un projet ESM sans dépendance de production', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.type, 'module')
  assert.equal(pkg.dependencies, undefined)
})

test('la fixture de catalogue couvre les cas tordus', async () => {
  const url = new URL('./fixtures/catalog-sample.json', import.meta.url)
  const cards = JSON.parse(await readFile(url, 'utf8'))
  const codes = cards.map((c) => c.riftbound_id)
  assert.equal(codes.filter((c) => c === 'ven-164-166').length, 2, 'doublon de données')
  assert.equal(codes.filter((c) => c === 'opp-259-298').length, 2, 'couple Metal')
  assert.ok(codes.includes('sfd-t03'), 'id irrégulier')
  assert.ok(codes.includes('unl-029a-219'), 'numéro à zéro de tête')
  assert.ok(codes.includes('unl-229*-219'), 'suffixe signature')
})
