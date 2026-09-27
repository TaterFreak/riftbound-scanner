import test from 'node:test'
import assert from 'node:assert/strict'
import { fetchAllCards, healthCheck } from '../catalog/update.js'

/** Faux fetch servant `pages` pages de `size` fiches numérotées. */
function fakeFetch({ pages, size, total }) {
  const calls = []
  const fn = async (url) => {
    const page = Number(new URL(url).searchParams.get('page'))
    calls.push(page)
    const items = Array.from({ length: size }, (_, i) => ({ n: (page - 1) * size + i }))
    return { ok: true, json: async () => ({ items, total, page, size, pages }) }
  }
  fn.calls = calls
  return fn
}

test('fetchAllCards suit la pagination jusqu’au bout', async () => {
  const fetchFn = fakeFetch({ pages: 3, size: 100, total: 300 })
  const items = await fetchAllCards(fetchFn)
  assert.equal(items.length, 300)
  assert.deepEqual(fetchFn.calls, [1, 2, 3])
})

test('fetchAllCards remonte une réponse HTTP en échec', async () => {
  const fetchFn = async () => ({ ok: false, status: 503 })
  await assert.rejects(() => fetchAllCards(fetchFn), /503/)
})

test('healthCheck accepte un premier import', () => {
  const next = [{ code: 'a-1-9', name: 'A', set: 'A', number: 1, tcgplayerId: '1' }]
  assert.deepEqual(healthCheck(next, null), { ok: true, problems: [] })
})

test('healthCheck refuse une chute de plus de 20 % du nombre de cartes', () => {
  const card = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const previous = Array.from({ length: 100 }, (_, i) => card(i))
  const next = Array.from({ length: 79 }, (_, i) => card(i))
  const result = healthCheck(next, previous)
  assert.equal(result.ok, false)
  assert.match(result.problems[0], /79.*100/)
})

test('healthCheck accepte une baisse inférieure au seuil', () => {
  const card = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const previous = Array.from({ length: 100 }, (_, i) => card(i))
  const next = Array.from({ length: 81 }, (_, i) => card(i))
  assert.equal(healthCheck(next, previous).ok, true)
})

test('healthCheck refuse plus de 1 % de champs obligatoires manquants', () => {
  const ok = (i) => ({ code: `a-${i}-9`, name: 'A', set: 'A', number: i, tcgplayerId: '1' })
  const next = Array.from({ length: 100 }, (_, i) => (i < 2 ? { ...ok(i), name: '' } : ok(i)))
  const result = healthCheck(next, null)
  assert.equal(result.ok, false)
  assert.match(result.problems[0], /champ/)
})

test('healthCheck refuse un catalogue vide', () => {
  assert.equal(healthCheck([], null).ok, false)
})
