// Cache d'exécution : la coquille et le catalogue sont pré-chargés, le WASM de
// Tesseract est mis en cache au premier passage réussi. La toute première ouverture
// exige donc du réseau ; les suivantes fonctionnent hors-ligne.
const CACHE = 'riftbound-v1'
const SHELL = [
  '.',
  'index.html',
  'styles.css',
  'manifest.webmanifest',
  'data/cards.json',
  'src/app.js',
  'src/ui/scan-view.js',
  'src/ui/list-view.js',
  'src/scan/machine.js',
  'src/scan/camera.js',
  'src/scan/ocr.js',
  'src/recognize/collector-code.js',
  'src/recognize/match.js',
  'src/recognize/variant.js',
  'src/recognize/language.js',
  'src/collection/entries.js',
  'src/collection/csv.js',
  'src/collection/store.js'
]

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((noms) => Promise.all(noms.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return
  event.respondWith(
    caches.match(event.request).then(
      (hit) =>
        hit ??
        fetch(event.request).then((response) => {
          if (response.ok) {
            const copie = response.clone()
            caches.open(CACHE).then((cache) => cache.put(event.request, copie))
          }
          return response
        })
    )
  )
})
