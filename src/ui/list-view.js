// Liste de la collection : consultation, correction ponctuelle, export CSV.
import { toCsv } from '../collection/csv.js'
import { updateEntry, removeEntry, incrementEntry } from '../collection/entries.js'

const CONDITIONS = ['NM', 'EX', 'GD', 'LP', 'PL', 'PO']
const LANGUES = ['en', 'fr']

export function createListView({ root, getState, setState }) {
  function telecharger() {
    const csv = toCsv(getState().entries)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const lien = document.createElement('a')
    lien.href = URL.createObjectURL(blob)
    lien.download = `riftbound-${new Date().toISOString().slice(0, 10)}.csv`
    lien.click()
    URL.revokeObjectURL(lien.href)
  }

  function selecteur(valeurs, courante, onChange) {
    const select = document.createElement('select')
    for (const valeur of valeurs) {
      const option = document.createElement('option')
      option.value = valeur
      option.textContent = valeur.toUpperCase()
      option.selected = valeur === courante
      select.append(option)
    }
    select.addEventListener('change', () => onChange(select.value))
    return select
  }

  // Carte empilée plutôt que ligne de tableau : sur un téléphone portrait, une table à
  // 8 colonnes déborde toujours de la largeur de l'écran, ce qui fait défiler toute la
  // page latéralement (en-tête d'onglets compris). Ici chaque contrôle passe à la ligne
  // (flex-wrap en CSS) au lieu d'élargir le conteneur.
  function carte(entry, i) {
    const item = document.createElement('li')
    item.className = 'carte'
    if (entry.unknown) item.classList.add('inconnue')

    const entete = document.createElement('div')
    entete.className = 'carte-entete'
    const nom = document.createElement('span')
    nom.textContent = entry.unknown ? `Inconnue (${entry.rawCode})` : entry.name
    const code = document.createElement('span')
    code.className = 'carte-code'
    code.textContent = entry.code
    entete.append(nom, code)

    const corps = document.createElement('div')
    corps.className = 'carte-corps'

    const set = document.createElement('span')
    set.textContent = entry.set
    const finition = document.createElement('span')
    finition.textContent = entry.finish === 'metal' ? 'Metal' : 'Normale'

    const quantite = document.createElement('span')
    quantite.className = 'carte-qte'
    const compte = document.createElement('span')
    compte.textContent = `×${entry.quantity}`
    const plus = document.createElement('button')
    plus.type = 'button'
    plus.textContent = '+'
    plus.setAttribute('aria-label', 'Ajouter un exemplaire')
    plus.addEventListener('click', () => setState({ entries: incrementEntry(getState().entries, i) }))
    quantite.append(compte, plus)

    const langue = selecteur(LANGUES, entry.language, (v) =>
      setState({ entries: updateEntry(getState().entries, i, { language: v }) })
    )

    const etat = selecteur(CONDITIONS, entry.condition, (v) =>
      setState({ entries: updateEntry(getState().entries, i, { condition: v }) })
    )

    const supprimer = document.createElement('button')
    supprimer.type = 'button'
    supprimer.textContent = 'Supprimer'
    supprimer.addEventListener('click', () =>
      setState({ entries: removeEntry(getState().entries, i) })
    )

    corps.append(set, finition, quantite, langue, etat, supprimer)
    item.append(entete, corps)
    return item
  }

  function render({ entries }) {
    root.replaceChildren()

    const barre = document.createElement('div')
    barre.className = 'reglages'
    const total = document.createElement('span')
    const exemplaires = entries.reduce((n, e) => n + e.quantity, 0)
    total.textContent = `${entries.length} lignes, ${exemplaires} cartes`
    const bouton = document.createElement('button')
    bouton.type = 'button'
    bouton.textContent = 'Télécharger le CSV'
    bouton.disabled = entries.length === 0
    bouton.addEventListener('click', telecharger)
    barre.append(total, bouton)
    root.append(barre)

    if (entries.length === 0) {
      const vide = document.createElement('p')
      vide.textContent = 'Aucune carte scannée pour le moment.'
      root.append(vide)
      return
    }

    const liste = document.createElement('ul')
    liste.className = 'liste-collection'
    entries.forEach((entry, i) => liste.append(carte(entry, i)))
    root.append(liste)
  }

  return { render }
}
