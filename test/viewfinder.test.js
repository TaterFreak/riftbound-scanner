import test from 'node:test'
import assert from 'node:assert/strict'
import { viewfinderSource, widenBand } from '../src/scan/viewfinder.js'

// Bande utilisée par l'app : voir BAND dans src/ui/scan-view.js et .viseur dans styles.css.
const BAND = { top: 0.78, height: 0.1, left: 0.04, right: 0.04 }

test('flux au même format que la boîte : aucun recadrage', () => {
  // video 1080x1440 (ratio 3:4), boîte 300x400 (même ratio) : scale = 300/1080 = 400/1440.
  // La bande 78 %-88 % de la boîte tombe donc exactement sur 78 %-88 % du flux.
  const result = viewfinderSource({
    videoWidth: 1080,
    videoHeight: 1440,
    boxWidth: 300,
    boxHeight: 400,
    band: BAND
  })
  assert.deepEqual(result, { sx: 43, sy: 1123, sWidth: 994, sHeight: 144 })
  // Vérification indépendante : 0.78 * 1440 = 1123.2, 0.1 * 1440 = 144, 0.04 * 1080 = 43.2.
  assert.equal(Math.round(0.78 * 1440), result.sy)
  assert.equal(Math.round(0.1 * 1440), result.sHeight)
})

test('flux plus large que la boîte : recadrage horizontal, correspondance verticale proportionnelle', () => {
  // video 1920x1080, boîte 300x400. scale = max(300/1920, 400/1080) = 400/1080 (contrainte en hauteur).
  // Hauteur affichée = 1080 * scale = 400 = boîte : aucun recadrage vertical, la bande verticale
  // reste donc la même fraction du flux que du naïf. Largeur affichée = 1920*scale = 711.1 > 300 :
  // recadrage horizontal, sx doit être décalé du bord.
  const result = viewfinderSource({
    videoWidth: 1920,
    videoHeight: 1080,
    boxWidth: 300,
    boxHeight: 400,
    band: BAND
  })
  assert.deepEqual(result, { sx: 587, sy: 842, sWidth: 745, sHeight: 108 })
  // Pas de recadrage vertical ici : la bande verticale coïncide avec le calcul naïf.
  assert.equal(Math.round(0.78 * 1080), result.sy)
  assert.equal(Math.round(0.1 * 1080), result.sHeight)
})

test('flux plus haut que la boîte : recadrage vertical (cas critique du bug)', () => {
  // video 1080x1920, boîte 300x400. scale = max(300/1080, 400/1920) = 300/1080 (contrainte en largeur).
  // Largeur affichée = 1080*scale = 300 = boîte : aucun recadrage horizontal.
  // Hauteur affichée = 1920*scale = 533.33 > 400 : recadrage vertical, offsetY = (1920 - 400/scale)/2 = 240.
  const result = viewfinderSource({
    videoWidth: 1080,
    videoHeight: 1920,
    boxWidth: 300,
    boxHeight: 400,
    band: BAND
  })
  assert.deepEqual(result, { sx: 43, sy: 1363, sWidth: 994, sHeight: 144 })
  // Le calcul naïf (l'ancien bug) aurait donné 0.78 * 1920 = 1497.6, très différent de 1363.2 :
  // c'est exactement l'écart que corrige ce module.
  const syNaif = Math.round(0.78 * 1920)
  assert.notEqual(syNaif, result.sy)
  assert.ok(Math.abs(syNaif - result.sy) > 100)
})

test('les marges latérales sont respectées', () => {
  // Même cas que ci-dessus : sx doit refléter offsetX (0 ici, pas de recadrage horizontal)
  // plus la marge gauche de 4 % de la boîte, ramenée à l'échelle du flux.
  const result = viewfinderSource({
    videoWidth: 1080,
    videoHeight: 1920,
    boxWidth: 300,
    boxHeight: 400,
    band: BAND
  })
  assert.equal(result.sx, Math.round(0.04 * 1080))
  // Et la largeur doit correspondre à 100 % - 4 % - 4 % = 92 % de la boîte, ramenée à
  // l'échelle du flux (scale = 300/1080 ici, pas de recadrage horizontal donc pas d'offset).
  const scale = 300 / 1080
  assert.equal(result.sWidth, Math.round((0.92 * 300) / scale))
})

test('la zone calculée reste toujours dans les bornes du flux', () => {
  const cas = [
    { videoWidth: 1080, videoHeight: 1440, boxWidth: 300, boxHeight: 400 },
    { videoWidth: 1920, videoHeight: 1080, boxWidth: 300, boxHeight: 400 },
    { videoWidth: 1080, videoHeight: 1920, boxWidth: 300, boxHeight: 400 },
    { videoWidth: 640, videoHeight: 480, boxWidth: 375, boxHeight: 812 },
    { videoWidth: 4032, videoHeight: 3024, boxWidth: 375, boxHeight: 500 }
  ]
  for (const dims of cas) {
    const result = viewfinderSource({ ...dims, band: BAND })
    assert.ok(result.sx >= 0, `sx négatif pour ${JSON.stringify(dims)}`)
    assert.ok(result.sy >= 0, `sy négatif pour ${JSON.stringify(dims)}`)
    assert.ok(result.sx + result.sWidth <= dims.videoWidth, `dépassement à droite pour ${JSON.stringify(dims)}`)
    assert.ok(result.sy + result.sHeight <= dims.videoHeight, `dépassement en bas pour ${JSON.stringify(dims)}`)
  }
})

test('dimensions de flux nulles ou absentes : renvoie null', () => {
  const boite = { boxWidth: 300, boxHeight: 400, band: BAND }
  assert.equal(viewfinderSource({ videoWidth: 0, videoHeight: 0, ...boite }), null)
  assert.equal(viewfinderSource({ videoWidth: undefined, videoHeight: undefined, ...boite }), null)
  assert.equal(viewfinderSource({ videoWidth: 1080, videoHeight: 0, ...boite }), null)
  assert.equal(viewfinderSource({ videoWidth: 1080, videoHeight: 1440, boxWidth: 0, boxHeight: 400, band: BAND }), null)
  assert.equal(viewfinderSource({ videoWidth: 1080, videoHeight: 1440, boxWidth: 300, boxHeight: 0, band: BAND }), null)
})

// widenBand : élargit la bande verticale autour de son centre, pour absorber l'écart de
// champ de vision entre l'aperçu vidéo et une photo plein capteur (voir grabViewfinder
// dans src/scan/camera.js). Les nombres ci-dessous sont des fractions binaires exactes
// (huitièmes, seizièmes, trente-deuxièmes) pour que les calculs à la main tombent juste
// en flottant, sans arrondi caché.

test('widenBand : élargissement normal, centre préservé, marges inchangées', () => {
  // top=0.25, height=0.25 -> centre = 0.375. Facteur 2 -> nouvelle hauteur 0.5,
  // nouveau top = 0.375 - 0.25 = 0.125. Tient dans [0, 1] : aucun bornage.
  const band = { top: 0.25, height: 0.25, left: 0.1, right: 0.1 }
  const result = widenBand(band, 2)
  assert.deepEqual(result, { top: 0.125, height: 0.5, left: 0.1, right: 0.1 })
})

test('widenBand : bande proche du bord haut, bornée à 0', () => {
  // top=0.03125, height=0.125 -> centre = 0.09375. Facteur 2 -> nouvelle hauteur 0.25,
  // top brut = 0.09375 - 0.125 = -0.03125 < 0 : bornage au bord haut.
  const band = { top: 0.03125, height: 0.125, left: 0, right: 0 }
  const result = widenBand(band, 2)
  assert.deepEqual(result, { top: 0, height: 0.25, left: 0, right: 0 })
})

test('widenBand : bande proche du bord bas, bornée à 1', () => {
  // top=0.875, height=0.125 (bande déjà collée au bord bas). centre = 0.9375.
  // Facteur 2 -> nouvelle hauteur 0.25, top brut = 0.9375 - 0.125 = 0.8125.
  // Borne haute = 1 - 0.25 = 0.75 < 0.8125 : bornage au bord bas.
  const band = { top: 0.875, height: 0.125, left: 0, right: 0 }
  const result = widenBand(band, 2)
  assert.deepEqual(result, { top: 0.75, height: 0.25, left: 0, right: 0 })
})

test('widenBand : facteur 1 ne change rien', () => {
  const band = { top: 0.3125, height: 0.125, left: 0.0625, right: 0.09375 }
  const result = widenBand(band, 1)
  assert.deepEqual(result, band)
})

test('widenBand : le centre est préservé quand il n\'y a pas de bornage', () => {
  // top=0.25, height=0.5 -> centre = 0.5. Facteur 1.5 -> nouvelle hauteur 0.75,
  // top brut = 0.5 - 0.375 = 0.125, dans [0, 0.25] : aucun bornage.
  const band = { top: 0.25, height: 0.5, left: 0.1, right: 0.2 }
  const result = widenBand(band, 1.5)
  assert.deepEqual(result, { top: 0.125, height: 0.75, left: 0.1, right: 0.2 })
  const centreOriginal = band.top + band.height / 2
  const centreElargi = result.top + result.height / 2
  assert.equal(centreElargi, centreOriginal)
})
