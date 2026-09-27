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

test('ne tranche pas quand les deux langues sont a egalite', () => {
  assert.equal(detectLanguage('a une'), null)
})

test('ignore la casse et la ponctuation', () => {
  assert.equal(detectLanguage('WHEN YOU PLAY ME, CHOOSE A PLAYER!'), 'en')
})
