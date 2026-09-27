import test from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../src/collection/store.js'

/** Faux IndexedDB minimal : un objet en mémoire suffit à vérifier le contrat. */
function fakeIndexedDB() {
  const data = new Map()
  return {
    data,
    open() {
      const request = {}
      queueMicrotask(() => {
        request.result = {
          transaction: () => ({
            objectStore: () => ({
              get(key) {
                const r = {}
                queueMicrotask(() => {
                  r.result = data.get(key)
                  r.onsuccess?.()
                })
                return r
              },
              put(value, key) {
                const r = {}
                queueMicrotask(() => {
                  data.set(key, value)
                  r.onsuccess?.()
                })
                return r
              }
            })
          }),
          objectStoreNames: { contains: () => true }
        }
        request.onsuccess?.()
      })
      return request
    }
  }
}

test('load renvoie un tableau vide quand rien n’est stocké', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  assert.deepEqual(await store.load(), [])
})

test('save puis load restituent les lignes', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  const entries = [{ code: 'unl-121-219', quantity: 1 }]
  await store.save(entries)
  assert.deepEqual(await store.load(), entries)
})

test('loadSettings renvoie les réglages par défaut', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  assert.deepEqual(await store.loadSettings(), {
    finish: 'normal',
    language: 'en',
    condition: 'NM',
    diagnostic: false
  })
})

test('saveSettings puis loadSettings restituent les réglages', async () => {
  const store = createStore({ indexedDB: fakeIndexedDB() })
  await store.saveSettings({ finish: 'metal', language: 'fr', condition: 'EX', diagnostic: true })
  assert.deepEqual(await store.loadSettings(), {
    finish: 'metal',
    language: 'fr',
    condition: 'EX',
    diagnostic: true
  })
})

test('sans IndexedDB, le magasin reste utilisable en mémoire', async () => {
  const store = createStore({ indexedDB: null })
  assert.equal(store.persistent, false)
  await store.save([{ code: 'a', quantity: 1 }])
  assert.deepEqual(await store.load(), [{ code: 'a', quantity: 1 }])
})
