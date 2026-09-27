// Sérialisation de la collection en CSV. Module pur.
// Point-virgule et BOM : Excel en configuration française ouvre alors le fichier
// correctement d'un double-clic, sans cesser d'être du CSV standard.

const COLUMNS = [
  ['quantity', (e) => e.quantity],
  ['name', (e) => e.name],
  ['set', (e) => e.set],
  ['set_label', (e) => e.setLabel],
  ['collector_number', (e) => e.number ?? ''],
  ['riftbound_id', (e) => e.code],
  ['variant', (e) => e.variant ?? ''],
  ['rarity', (e) => e.rarity],
  ['type', (e) => e.type],
  ['domain', (e) => (e.domain ?? []).join('|')],
  ['finish', (e) => e.finish],
  ['language', (e) => e.language ?? ''],
  ['condition', (e) => e.condition],
  ['price', () => ''],
  ['riftcodex_id', (e) => e.riftcodexId ?? ''],
  ['tcgplayer_id', (e) => e.tcgplayerId ?? ''],
  ['raw_code', (e) => e.rawCode ?? ''],
  ['scanned_at', (e) => e.scannedAt]
]

// Neutralise l'injection de formule : un tableur interprète une cellule commençant
// par =, +, - ou @ comme une formule (le raw_code d'une carte inconnue vient de l'OCR,
// donc non maîtrisé). Une apostrophe en tête force une lecture en texte, sans autre effet.
const FORMULA_LEAD = /^[=+\-@]/

const escape = (value) => {
  const raw = String(value ?? '')
  const text = FORMULA_LEAD.test(raw) ? `'${raw}` : raw
  return /[";\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

export function toCsv(entries) {
  const header = COLUMNS.map(([name]) => name).join(';')
  const rows = entries.map((e) => COLUMNS.map(([, read]) => escape(read(e))).join(';'))
  return '﻿' + [header, ...rows].join('\r\n') + '\r\n'
}
