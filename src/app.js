// Câblage de l'application : état central, chargement du catalogue, bascule d'onglets.
import { buildIndex } from './recognize/match.js'
import { createScanMachine } from './scan/machine.js'
import { createStore } from './collection/store.js'
import { createScanView } from './ui/scan-view.js'
import { createListView } from './ui/list-view.js'

const store = createStore({ indexedDB: globalThis.indexedDB ?? null })
const etat = {
  entries: [],
  settings: { finish: 'normal', language: 'en', condition: 'NM', diagnostic: false }
}

const getState = () => etat
const abonnes = []

// Point de notification unique : tout changement d'état, y compris le chargement
// initial plus bas, passe par ici. Un abonné ajouté plus tard est ainsi couvert
// sans qu'on ait à dupliquer son appel à côté de chaque mise à jour.
function notifierTous() {
  for (const notifier of abonnes) notifier(etat)
}

function setState(patch) {
  Object.assign(etat, patch)
  if (patch.entries) store.save(etat.entries)
  if (patch.settings) store.saveSettings(etat.settings)
  notifierTous()
}

const status = document.querySelector('#etat-scan')
const onStatus = (message) => {
  status.textContent = message
}

const { cards } = await (await fetch('data/cards.json')).json()
const index = buildIndex(cards)
const machine = createScanMachine({ index })

etat.entries = await store.load()
etat.settings = await store.loadSettings()
if (!store.persistent) {
  onStatus('Stockage indisponible : la collection sera perdue à la fermeture.')
}

const scanView = createScanView({
  root: document.querySelector('#vue-scan'),
  machine,
  getState,
  setState,
  onStatus
})
const listView = createListView({
  root: document.querySelector('#vue-liste'),
  getState,
  setState
})
abonnes.push(listView.render)
abonnes.push(({ entries }) => {
  document.querySelector('#compteur').textContent = entries.reduce((n, e) => n + e.quantity, 0)
})

function afficher(onglet) {
  const scan = onglet === 'scan'
  document.querySelector('#vue-scan').hidden = !scan
  document.querySelector('#vue-liste').hidden = scan
  document.querySelector('#onglet-scan').classList.toggle('actif', scan)
  document.querySelector('#onglet-liste').classList.toggle('actif', !scan)
  if (scan) scanView.start()
  else scanView.stop()
}

document.querySelector('#onglet-scan').addEventListener('click', () => afficher('scan'))
document.querySelector('#onglet-liste').addEventListener('click', () => afficher('liste'))

notifierTous()
afficher('scan')

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js')
