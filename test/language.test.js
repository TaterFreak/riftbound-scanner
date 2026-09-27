import test from 'node:test'
import assert from 'node:assert/strict'
import { detectLanguage } from '../src/recognize/language.js'

test('reconnait un texte de regles anglais', () => {
  assert.equal(
    detectLanguage('When you play me, choose a player. They discard 1.'),
    'en'
  )
})

test('reconnait un texte de regles francais', () => {
  assert.equal(
    detectLanguage('Quand vous me jouez, choisissez un joueur. Il defausse 1 carte.'),
    'fr'
  )
})

test('tolere l\'absence d\'accents, que l\'OCR perd souvent', () => {
  assert.equal(
    detectLanguage('Quand vous me jouez, choisissez un joueur. Il defausse une carte.'),
    'fr'
  )
})

test('ne tranche pas sur un texte trop court', () => {
  assert.equal(detectLanguage('Vi'), null)
  assert.equal(detectLanguage(''), null)
})

test('ne tranche pas quand les deux langues sont a egalite (scores egaux >= MIN_HITS)', () => {
  // 'the' 'play' 'draw' (3 en) vs 'quand' 'vous' 'joueur' (3 fr) => 3-3, ecart 0
  assert.equal(detectLanguage('the play draw quand vous joueur'), null)
})

test('ne tranche pas quand la marge est insuffisante (ecart = 1)', () => {
  // 'when' 'you' 'play' (3 en) vs 'quand' 'vous' (2 fr) => 3-2, ecart 1 < MIN_MARGIN
  assert.equal(detectLanguage('when you play quand vous'), null)
})

test('tranche avec une marge tout juste suffisante (ecart = 2)', () => {
  // 'when' 'you' 'play' (3 en) vs 'quand' (1 fr) => 3-1, ecart 2 >= MIN_MARGIN => 'en'
  assert.equal(detectLanguage('when you play quand'), 'en')
})

test('ignore la casse et la ponctuation', () => {
  assert.equal(detectLanguage('WHEN YOU PLAY ME, CHOOSE A PLAYER!'), 'en')
})
