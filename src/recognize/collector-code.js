// Lecture du code de collection imprimé en bas de carte, à partir d'un texte d'OCR.
// Module pur : aucune entrée-sortie.

/**
 * Prefixes littéraux rencontrés dans le catalogue (jetons, promos, cartes spéciales).
 * Liste fermée volontairement : l'OCR confond `1` et `i`, donc « i2i » doit être lu
 * comme le nombre 121 et non comme le prefixe « i » suivi de 21.
 * Le test `collector-code.test.js` verifie cette liste contre data/cards.json.
 */
export const KNOWN_PREFIXES = ['sp', 't', 'r']

/**
 * Ensembles valides du catalogue.
 * Liste fermée : validée contre data/cards.json pour rejeter les faux positifs.
 */
const KNOWN_SETS = ['jdg', 'ogn', 'ogs', 'opp', 'pr', 'sfd', 'unl', 'ven']

const SEPARATORS = /[\s/·•_.,:;|—–]+/g
const LETTER_TO_DIGIT = { o: '0', i: '1', l: '1', s: '5', b: '8', z: '2' }
const DIGIT_TO_LETTER = { 0: 'o', 1: 'i', 5: 's', 8: 'b' }

const toDigits = (s) => s.replace(/[a-z]/g, (ch) => LETTER_TO_DIGIT[ch] ?? ch)
const toLetters = (s) => s.replace(/[0-9]/g, (ch) => DIGIT_TO_LETTER[ch] ?? ch)
const stripLeadingZeros = (s) => s.replace(/^0+(?=\d)/, '')

/** Forme de référence d'un code : minuscule, sans zéros de tête sur le numéro. */
export function canonicalCode(code) {
  if (typeof code !== 'string') return null
  const parts = code.toLowerCase().split('-')
  if (parts.length < 2) return code.toLowerCase()
  parts[1] = parts[1].replace(/\d+/, (n) => stripLeadingZeros(n))
  return parts.join('-')
}

/** Sépare un segment central en { prefix, number, variant }. */
function splitCore(core) {
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

  if (!cleaned) return null

  const candidates = []

  for (const knownSet of KNOWN_SETS) {
    const setsToSearch = [knownSet]

    // Ajouter les variantes OCR possibles du set
    const setWithOcrErrors = knownSet.replace(/[a-z]/g, ch => {
      for (const [digit, letter] of Object.entries(DIGIT_TO_LETTER)) {
        if (letter === ch) return digit
      }
      return ch
    })
    if (setWithOcrErrors !== knownSet) {
      setsToSearch.push(setWithOcrErrors)
    }

    for (const searchSet of setsToSearch) {
      let searchPos = 0
      while ((searchPos = cleaned.indexOf(searchSet, searchPos)) !== -1) {
        const beforeIsValid = searchPos === 0 || cleaned[searchPos - 1] === '-'
        const afterIndex = searchPos + searchSet.length
        const afterIsValid = afterIndex >= cleaned.length || cleaned[afterIndex] === '-'

        if (beforeIsValid && afterIsValid) {
          const afterSet = cleaned.slice(afterIndex + 1)
          const numberMatch = afterSet.match(/^([a-z0-9*]{1,6})(?:-([a-z0-9]{1,4}))?(?:$|-)/)
          if (numberMatch) {
            const core = splitCore(numberMatch[1])
            if (core) {
              const totalRaw = numberMatch[2] ? toDigits(numberMatch[2]) : null
              const total = totalRaw && /^\d+$/.test(totalRaw) ? totalRaw : null

              const head = `${knownSet}-${core.prefix}${core.number}${core.variant}`
              const code = total ? `${head}-${total}` : head

              candidates.push({
                code,
                hasTotal: !!total,
                position: searchPos
              })
            }
          }
        }

        searchPos += 1
      }
    }
  }

  if (candidates.length === 0) return null

  candidates.sort((a, b) => {
    if (b.hasTotal !== a.hasTotal) return b.hasTotal - a.hasTotal
    return b.position - a.position
  })

  return candidates[0].code
}
