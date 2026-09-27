import test from 'node:test'
import assert from 'node:assert/strict'
import { toCsv } from '../src/collection/csv.js'

const entry = {
  code: 'unl-121-219',
  name: 'Bewitching Spirit',
  set: 'UNL',
  setLabel: 'Unleashed',
  number: 121,
  rarity: 'Common',
  type: 'Unit',
  domain: ['Chaos'],
  riftcodexId: 'abc',
  tcgplayerId: '685592',
  variant: null,
  finish: 'normal',
  language: 'en',
  condition: 'NM',
  quantity: 2,
  rawCode: 'unl-121-219',
  scannedAt: '2026-09-27T10:00:00.000Z',
  unknown: false
}

const lines = (csv) => csv.replace(/^﻿/, '').trimEnd().split('\r\n')

test('commence par un BOM UTF-8', () => {
  assert.ok(toCsv([]).startsWith('﻿'))
})

test('écrit l’en-tête même sans ligne', () => {
  assert.equal(lines(toCsv([])).length, 1)
  assert.match(lines(toCsv([]))[0], /^quantity;name;set;/)
})

test('sépare les colonnes par des points-virgules', () => {
  const [, row] = lines(toCsv([entry]))
  assert.equal(row.split(';')[0], '2')
  assert.equal(row.split(';')[1], 'Bewitching Spirit')
  assert.equal(row.split(';')[5], 'unl-121-219')
})

test('laisse la colonne price vide', () => {
  const header = lines(toCsv([entry]))[0].split(';')
  const row = lines(toCsv([entry]))[1].split(';')
  assert.equal(row[header.indexOf('price')], '')
})

test('joint les domaines par une barre verticale', () => {
  const multi = { ...entry, domain: ['Fury', 'Order'] }
  const header = lines(toCsv([multi]))[0].split(';')
  const row = lines(toCsv([multi]))[1].split(';')
  assert.equal(row[header.indexOf('domain')], 'Fury|Order')
})

test('protège un nom contenant un point-virgule', () => {
  const tricky = { ...entry, name: 'Gold; Buff' }
  assert.match(lines(toCsv([tricky]))[1], /"Gold; Buff"/)
})

test('double les guillemets internes', () => {
  const tricky = { ...entry, name: 'Le "Boss"' }
  assert.match(lines(toCsv([tricky]))[1], /"Le ""Boss"""/)
})

test('exporte une carte inconnue avec son code brut', () => {
  const unknown = { ...entry, unknown: true, name: '', code: 'zzz-9-9', rawCode: 'zzz-9-9' }
  const header = lines(toCsv([unknown]))[0].split(';')
  const row = lines(toCsv([unknown]))[1].split(';')
  assert.equal(row[header.indexOf('raw_code')], 'zzz-9-9')
})

test('utilise des fins de ligne CRLF', () => {
  assert.ok(toCsv([entry]).includes('\r\n'))
})

test('neutralise une valeur commençant par un signe de formule', () => {
  const tricky = { ...entry, name: '=SUM(A1:A9)' }
  const header = lines(toCsv([tricky]))[0].split(';')
  const row = lines(toCsv([tricky]))[1].split(';')
  assert.equal(row[header.indexOf('name')], "'=SUM(A1:A9)")
})

test('laisse une valeur légitime inchangée', () => {
  const header = lines(toCsv([entry]))[0].split(';')
  const row = lines(toCsv([entry]))[1].split(';')
  assert.equal(row[header.indexOf('name')], 'Bewitching Spirit')
})
