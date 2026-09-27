// Lecture du code de collection imprimé en bas de carte, à partir d'un texte d'OCR.
// Module pur : aucune entrée-sortie.

/**
 * Préfixes littéraux rencontrés dans le catalogue (jetons, promos, cartes spéciales).
 * Liste fermée volontairement : l'OCR confond `1` et `i`, donc « i2i » doit être lu
 * comme le nombre 121 et non comme le préfixe « i » suivi de 21.
 * Le test `collector-code.test.js` vérifie cette liste contre data/cards.json.
 */
export const KNOWN_PREFIXES = ['sp', 't', 'r']

const SEPARATORS = /[\s/·•_.,:;|—–]+/g
const LETTER_TO_DIGIT = { o: '0', i: '1', l: '1', s: '5', b: '8', z: '2' }
const DIGIT_TO_LETTER = { 0: 'o', 1: 'i', 5: 's', 8: 'b' }

const toDigits = (s) => s.replace(/[a-z]/g, (ch) => LETTER_TO_DIGIT[ch] ?? ch)
const toLetters = (s) => s.replace(/[0-9]/g, (ch) => DIGIT_TO_LETTER[ch] ?? ch)
const stripLeadingZeros = (s) => s.replace(/^0+(?=\d)/, '')

/** Forme de référence d'un code : minuscule, sans zéros de tête sur le numéro. */
export function canonicalCode(code) {
  const parts = String(code).toLowerCase().split('-')
  if (parts.length < 2) return String(code).toLowerCase()
  parts[1] = parts[1].replace(/\d+/, (n) => stripLeadingZeros(n))
  return parts.join('-')
}

/** Sépare un segment central en { prefix, number, variant }. */
function splitCore(core) {
  // Seules lettres de variante présentes au catalogue : a (112 cartes), b (8), * (36).
  // La restriction est indispensable : sans elle, « i2i » — lecture courante de 121 —
  // serait lu comme le nombre 12 suivi d'une variante « i ».
  const variantMatch = core.match(/[ab*]$/)
  const variant = variantMatch && core.length > 1 ? variantMatch[0] : ''
  const withoutVariant = variant ? core.slice(0, -1) : core

  const prefix = KNOWN_PREFIXES.find((p) => withoutVariant.startsWith(p)) ?? ''
  const digits = toDigits(withoutVariant.slice(prefix.length))

  if (!/^\d+$/.test(digits)) return null
  return { prefix, number: stripLeadingZeros(digits), variant: variant === '*' ? '*' : variant }
}

export function parseCollectorCode(rawOcrText) {
  if (typeof rawOcrText !== 'string') return null

  const cleaned = rawOcrText
    .toLowerCase()
    .replace(SEPARATORS, '-')
    .replace(/[^a-z0-9*-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')

  // On cherche le code où qu'il soit dans la ligne, artiste et mentions légales compris.
  const shape = /(?:^|-)([a-z0-9]{2,4})-([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:-|$)/
  const found = cleaned.match(shape)
  if (!found) return null

  const set = toLetters(found[1])
  if (!/^[a-z]{2,4}$/.test(set)) return null

  const core = splitCore(found[2])
  if (!core) return null

  const totalRaw = found[3] ? toDigits(found[3]) : null
  const total = totalRaw && /^\d+$/.test(totalRaw) ? totalRaw : null

  const head = `${set}-${core.prefix}${core.number}${core.variant}`
  return total ? `${head}-${total}` : head
}
