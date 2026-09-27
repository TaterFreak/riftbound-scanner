// Géométrie pure du viseur. Aucun DOM, aucune horloge, aucune I/O : ce module ne fait
// que reproduire, en pixels du flux, la zone que `object-fit: cover` affiche dans la boîte.

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max)
}

/**
 * Calcule la zone source (repère du flux vidéo) correspondant à une bande exprimée en
 * fractions de la boîte affichée (celle que l'utilisateur voit, après le recadrage de
 * `object-fit: cover`).
 *
 * `object-fit: cover` met le flux à l'échelle par le plus grand des deux facteurs
 * boxWidth/videoWidth et boxHeight/videoHeight, centre le résultat, et coupe le
 * débordement à parts égales de chaque côté. On inverse ici cette transformation :
 * on part d'un rectangle de la boîte affichée et on retrouve le rectangle du flux dont
 * il est l'image.
 *
 * @param {{videoWidth:number, videoHeight:number, boxWidth:number, boxHeight:number,
 *          band:{top:number, height:number, left:number, right:number}}} params
 * @returns {{sx:number, sy:number, sWidth:number, sHeight:number}|null}
 */
export function viewfinderSource({ videoWidth, videoHeight, boxWidth, boxHeight, band }) {
  if (!(videoWidth > 0) || !(videoHeight > 0) || !(boxWidth > 0) || !(boxHeight > 0)) {
    return null
  }

  // Échelle appliquée par `cover` : la plus grande des deux, pour que le flux couvre
  // toute la boîte (et déborde forcément sur l'autre axe, sauf ratio identique).
  const scale = Math.max(boxWidth / videoWidth, boxHeight / videoHeight)

  // Portion du flux réellement visible dans la boîte, et son décalage (le débordement
  // coupé de chaque côté, réparti pour moitié avant, moitié après).
  const visibleWidth = boxWidth / scale
  const visibleHeight = boxHeight / scale
  const offsetX = (videoWidth - visibleWidth) / 2
  const offsetY = (videoHeight - visibleHeight) / 2

  // La bande, en pixels de la boîte affichée.
  const bandLeftPx = band.left * boxWidth
  const bandRightPx = band.right * boxWidth
  const bandTopPx = band.top * boxHeight
  const bandHeightPx = band.height * boxHeight
  const bandWidthPx = Math.max(boxWidth - bandLeftPx - bandRightPx, 0)

  // Passage boîte -> flux : chaque pixel de la boîte visible vaut 1/scale pixel de flux,
  // à ajouter au décalage du recadrage.
  const rawSx = offsetX + bandLeftPx / scale
  const rawSy = offsetY + bandTopPx / scale
  const rawWidth = bandWidthPx / scale
  const rawHeight = bandHeightPx / scale

  // Garde-fou : toujours dans les bornes du flux, même en cas d'arrondi ou de bande
  // mal réglée (marges qui se chevauchent, boîte disproportionnée...).
  const sx = Math.round(clamp(rawSx, 0, videoWidth))
  const sy = Math.round(clamp(rawSy, 0, videoHeight))
  const sWidth = Math.round(clamp(rawWidth, 0, videoWidth - sx))
  const sHeight = Math.round(clamp(rawHeight, 0, videoHeight - sy))

  return { sx, sy, sWidth, sHeight }
}
