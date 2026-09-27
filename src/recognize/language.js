// Détection best-effort de la langue d'impression, par mots-outils du texte de règles.
// Aucune base publique ne fournit les noms traduits : c'est le seul signal disponible.
// Module pur.

const STOPWORDS = {
  en: ['when', 'you', 'the', 'play', 'choose', 'card', 'your', 'may', 'gain', 'discard', 'draw', 'unit', 'and', 'target'],
  fr: ['quand', 'vous', 'une', 'les', 'des', 'joueur', 'carte', 'vos', 'avec', 'puis', 'defausse', 'choisissez', 'unite', 'cible']
}

const MIN_HITS = 2
const MIN_MARGIN = 2

const normalize = (text) =>
  String(text)
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .split(/[^a-z]+/)
    .filter(Boolean)

export function detectLanguage(text) {
  const words = normalize(text)
  if (words.length < 3) return null

  const score = (list) => words.filter((w) => list.includes(w)).length
  const en = score(STOPWORDS.en)
  const fr = score(STOPWORDS.fr)

  if (Math.max(en, fr) < MIN_HITS) return null
  if (Math.abs(en - fr) < MIN_MARGIN) return null
  return en > fr ? 'en' : 'fr'
}
