// Persistance de la collection et des réglages. Seule couche d'entrées-sorties du
// dossier collection/. `indexedDB` est injecté pour rester testable.

const DB_NAME = 'riftbound-scanner'
const DB_VERSION = 1
const STORE = 'state'
const DEFAULT_SETTINGS = { finish: 'normal', language: 'en', condition: 'NM' }

const promisify = (request) =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })

export function createStore({ indexedDB }) {
  // Repli en mémoire : mode navigation privée, stockage bloqué, tests.
  if (!indexedDB) {
    const memory = new Map()
    return {
      persistent: false,
      async load() {
        return memory.get('entries') ?? []
      },
      async save(entries) {
        memory.set('entries', entries)
      },
      async loadSettings() {
        return memory.get('settings') ?? { ...DEFAULT_SETTINGS }
      },
      async saveSettings(settings) {
        memory.set('settings', settings)
      }
    }
  }

  let db = null

  async function open() {
    if (db) return db
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const result = request.result
      if (!result.objectStoreNames.contains(STORE)) result.createObjectStore(STORE)
    }
    db = await promisify(request)
    return db
  }

  async function read(key, fallback) {
    const connection = await open()
    const store = connection.transaction(STORE, 'readonly').objectStore(STORE)
    const value = await promisify(store.get(key))
    return value ?? fallback
  }

  async function write(key, value) {
    const connection = await open()
    const store = connection.transaction(STORE, 'readwrite').objectStore(STORE)
    await promisify(store.put(value, key))
  }

  return {
    persistent: true,
    load: () => read('entries', []),
    save: (entries) => write('entries', entries),
    loadSettings: () => read('settings', { ...DEFAULT_SETTINGS }),
    saveSettings: (settings) => write('settings', settings)
  }
}
