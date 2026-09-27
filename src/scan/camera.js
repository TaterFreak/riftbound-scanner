// Accès caméra et découpe de la zone du viseur.
import { viewfinderSource } from './viewfinder.js'

// Hauteur minimale (en pixels) de la bande dessinée dans le canvas avant l'OCR.
// Tesseract a besoin d'une hauteur de texte d'au moins une trentaine de pixels ;
// avec la bande qui ne fait qu'environ 10 % de la hauteur du flux, il faut l'agrandir
// nettement pour que le texte y soit assez grand.
const MIN_BAND_HEIGHT_PX = 200

export class CameraError extends Error {
  constructor(message) {
    super(message)
    this.name = 'CameraError'
  }
}

export async function startCamera(videoElement) {
  if (!globalThis.isSecureContext) {
    throw new CameraError(
      "La caméra exige une connexion sécurisée. Ouvre la page en HTTPS, ou via le " +
        'port forwarding de chrome://inspect en développement.'
    )
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new CameraError("Ce navigateur n'expose pas la caméra.")
  }

  let stream
  try {
    // Résolution nettement plus haute et mise au point continue : la netteté à la
    // distance où l'on tient une carte en dépend, comme le fait l'app caméra native.
    // Ces contraintes ne sont pas supportées partout ; `ideal` les laisse dégrader
    // proprement plutôt que de faire échouer le démarrage.
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 3840 },
        height: { ideal: 2160 },
        focusMode: { ideal: 'continuous' }
      },
      audio: false
    })
  } catch (cause) {
    if (cause.name === 'NotAllowedError') {
      throw new CameraError("L'accès à la caméra a été refusé.")
    }
    throw new CameraError(`La caméra n'a pas pu démarrer : ${cause.message}`)
  }

  // Certains appareils n'acceptent la mise au point continue qu'au travers
  // d'`applyConstraints`, une fois le flux obtenu, et pas dans `getUserMedia`.
  // Silencieux et sans conséquence sur le démarrage si ce n'est pas supporté.
  const [track] = stream.getVideoTracks()
  try {
    await track?.applyConstraints({ advanced: [{ focusMode: 'continuous' }] })
  } catch {
    // Tant pis : l'image sera moins nette, mais la caméra reste utilisable.
  }

  videoElement.srcObject = stream
  await videoElement.play()

  return {
    stop() {
      for (const track of stream.getTracks()) track.stop()
      videoElement.srcObject = null
    }
  }
}

/**
 * Ne recopie que la bande du viseur. Réduire la surface analysée est ce qui rend
 * l'OCR tenable sur téléphone : environ 5 % de l'image au lieu de la totalité.
 *
 * `band` est exprimée en fractions de la boîte *affichée* (voir BAND dans
 * src/ui/scan-view.js et .viseur dans styles.css), pas du flux : la taille affichée de
 * l'élément vidéo (`getBoundingClientRect`) peut différer de ses dimensions
 * intrinsèques à cause du recadrage `object-fit: cover` appliqué par le CSS.
 * `viewfinderSource` fait la conversion.
 */
export function grabViewfinder(videoElement, canvas, band) {
  const videoWidth = videoElement.videoWidth
  const videoHeight = videoElement.videoHeight
  const { width: boxWidth, height: boxHeight } = videoElement.getBoundingClientRect()

  const source = viewfinderSource({ videoWidth, videoHeight, boxWidth, boxHeight, band })
  if (!source) return
  const { sx, sy, sWidth, sHeight } = source
  if (!sWidth || !sHeight) return

  // Agrandissement avant l'OCR : Tesseract lit mal un texte dont les caractères font
  // moins d'une trentaine de pixels de haut. On vise une bande d'au moins 200 px de
  // haut dans le canvas, en conservant le rapport largeur/hauteur d'origine.
  const drawScale = Math.max(MIN_BAND_HEIGHT_PX / sHeight, 1)
  const drawWidth = Math.round(sWidth * drawScale)
  const drawHeight = Math.round(sHeight * drawScale)

  canvas.width = drawWidth
  canvas.height = drawHeight

  const context = canvas.getContext('2d', { willReadFrequently: true })
  context.drawImage(videoElement, sx, sy, sWidth, sHeight, 0, 0, drawWidth, drawHeight)
}

/** Recopie l'image entière. Employée uniquement par la détection de langue, ponctuelle. */
export function grabFull(videoElement, canvas) {
  const width = videoElement.videoWidth
  const height = videoElement.videoHeight
  if (!width || !height) return
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d', { willReadFrequently: true }).drawImage(videoElement, 0, 0)
}
