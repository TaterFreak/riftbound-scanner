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

  function ligne(entry, i) {
    const tr = document.createElement('tr')
    if (entry.unknown) tr.classList.add('inconnue')

    const nom = document.createElement('td')
    nom.textContent = entry.unknown ? `Inconnue (${entry.rawCode})` : entry.name
    const code = document.createElement('td')
    code.textContent = entry.code
    const set = document.createElement('td')
    set.textContent = entry.set
    const finition = document.createElement('td')
    finition.textContent = entry.finish === 'metal' ? 'Metal' : 'Normale'

    const quantite = document.createElement('td')
    quantite.textContent = entry.quantity
    const plus = document.createElement('button')
    plus.type = 'button'
    plus.textContent = '+'
    plus.addEventListener('click', () => setState({ entries: incrementEntry(getState().entries, i) }))
    quantite.append(plus)

    const langue = document.createElement('td')
    langue.append(
      selecteur(LANGUES, entry.language, (v) =>
        setState({ entries: updateEntry(getState().entries, i, { language: v }) })
      )
    )

    const etat = document.createElement('td')
    etat.append(
      selecteur(CONDITIONS, entry.condition, (v) =>
        setState({ entries: updateEntry(getState().entries, i, { condition: v }) })
      )
    )

    const actions = document.createElement('td')
    const supprimer = document.createElement('button')
    supprimer.type = 'button'
    supprimer.textContent = 'Supprimer'
    supprimer.addEventListener('click', () =>
      setState({ entries: removeEntry(getState().entries, i) })
    )
    actions.append(supprimer)

    tr.append(nom, code, set, finition, quantite, langue, etat, actions)
    return tr
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

    const table = document.createElement('table')
    const thead = document.createElement('thead')
    const entetes = ['Nom', 'Code', 'Set', 'Finition', 'Qté', 'Langue', 'État', '']
    const tr = document.createElement('tr')
    for (const texte of entetes) {
      const th = document.createElement('th')
      th.textContent = texte
      tr.append(th)
    }
    thead.append(tr)
    const tbody = document.createElement('tbody')
    entries.forEach((entry, i) => tbody.append(ligne(entry, i)))
    table.append(thead, tbody)
    root.append(table)
  }

  return { render }
}
