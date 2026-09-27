// Accès caméra et découpe de la zone du viseur.
import { viewfinderSource, widenBand } from './viewfinder.js'

// Hauteur minimale (en pixels) de la bande dessinée dans le canvas avant l'OCR.
// Tesseract a besoin d'une hauteur de texte d'au moins une trentaine de pixels ;
// avec la bande qui ne fait qu'environ 10 % de la hauteur du flux, il faut l'agrandir
// nettement pour que le texte y soit assez grand.
const MIN_BAND_HEIGHT_PX = 200

// Élargissement de la bande appliqué uniquement en mode photo (voir widenBand dans
// viewfinder.js) : le champ de vision d'une photo plein capteur ne coïncide pas
// forcément avec celui de l'aperçu vidéo (16:9 recadré à l'écran, 4:3 plein capteur
// à la prise de vue). La résolution photo est largement suffisante pour absorber cette
// marge de sécurité.
const PHOTO_BAND_WIDEN_FACTOR = 1.6

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

  const grab = createFrameGrabber(videoElement, track)

  return {
    grab,
    stop() {
      for (const track of stream.getTracks()) track.stop()
      videoElement.srcObject = null
    }
  }
}

/**
 * Tente d'obtenir un chemin de capture photo plein capteur via l'API `ImageCapture`,
 * bien plus définie que le flux vidéo (`video.videoWidth/videoHeight`), que le
 * navigateur bride typiquement à 1080p alors que le capteur fait 12 mégapixels ou plus.
 *
 * Retourne `null` si l'API est absente ou si son interrogation échoue : l'appelant doit
 * alors se rabattre sur le flux vidéo, qui reste toujours disponible. Silencieux à
 * dessein : cette tentative ne doit jamais empêcher l'application de fonctionner.
 */
async function createPhotoCapture(track) {
  if (typeof globalThis.ImageCapture !== 'function' || !track) return null
  try {
    const imageCapture = new globalThis.ImageCapture(track)
    const capabilities = await imageCapture.getPhotoCapabilities()
    const maxWidth = capabilities?.imageWidth?.max
    const photoSettings = maxWidth ? { imageWidth: maxWidth } : {}
    return { imageCapture, photoSettings }
  } catch {
    return null
  }
}

/**
 * Construit la fonction de capture d'une trame analysable : une vraie photo plein
 * capteur si `ImageCapture` fonctionne, sinon l'image du flux vidéo (repli silencieux).
 * Le résultat expose `mode` ('photo' ou 'video') pour que le diagnostic puisse afficher
 * le chemin réellement emprunté, et n'autorise jamais deux captures concurrentes.
 */
function createFrameGrabber(videoElement, track) {
  let photoCapturePromise = null
  let busy = false

  async function grabVideoFrame() {
    const width = videoElement.videoWidth
    const height = videoElement.videoHeight
    if (!width || !height) return null
    return { mode: 'video', drawable: videoElement, width, height }
  }

  return async function grab() {
    if (busy) return null
    busy = true
    try {
      // Résolue une seule fois (coût de `getPhotoCapabilities`), puis réutilisée à
      // chaque passe. Si `takePhoto()` échoue plus loin, on retente quand même à la
      // passe suivante : un raté ponctuel ne doit pas condamner tout le scan au repli.
      if (photoCapturePromise === null) photoCapturePromise = createPhotoCapture(track)
      const photoCapture = await photoCapturePromise

      if (photoCapture) {
        try {
          const blob = await photoCapture.imageCapture.takePhoto(photoCapture.photoSettings)
          const bitmap = await createImageBitmap(blob)
          return { mode: 'photo', drawable: bitmap, width: bitmap.width, height: bitmap.height }
        } catch {
          // `takePhoto()` a échoué à l'usage (appareil qui se dérobe en cours de scan) :
          // repli sur le flux vidéo pour cette passe, sans faire échouer le scan.
        }
      }
      return await grabVideoFrame()
    } finally {
      busy = false
    }
  }
}

/**
 * Ne recopie que la bande du viseur. Réduire la surface analysée est ce qui rend
 * l'OCR tenable sur téléphone : environ 5 % de l'image au lieu de la totalité.
 *
 * `band` est exprimée en fractions de la boîte *affichée* (voir BAND dans
 * src/ui/scan-view.js et .viseur dans styles.css), pas de la source : la taille affichée
 * de l'élément vidéo (`getBoundingClientRect`) peut différer des dimensions intrinsèques
 * de la trame à cause du recadrage `object-fit: cover` appliqué par le CSS.
 * `viewfinderSource` fait la conversion.
 *
 * `frame` est le résultat de `camera.grab()` (voir `createFrameGrabber` ci-dessus) :
 * soit une vraie photo plein capteur (`mode: 'photo'`), soit, en repli, l'image du flux
 * vidéo (`mode: 'video'`). En mode photo, le champ de vision de la prise de vue ne
 * coïncide pas forcément avec celui de l'aperçu (16:9 recadré à l'écran, 4:3 plein
 * capteur à la prise de vue) : on élargit alors la bande verticale avant de calculer la
 * zone, pour absorber cet écart. En mode vidéo, la géométrie est exacte : la bande est
 * conservée telle quelle.
 */
export function grabViewfinder(videoElement, canvas, band, frame) {
  const { width: boxWidth, height: boxHeight } = videoElement.getBoundingClientRect()
  const effectiveBand = frame.mode === 'photo' ? widenBand(band, PHOTO_BAND_WIDEN_FACTOR) : band

  const source = viewfinderSource({
    videoWidth: frame.width,
    videoHeight: frame.height,
    boxWidth,
    boxHeight,
    band: effectiveBand
  })
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
  context.drawImage(frame.drawable, sx, sy, sWidth, sHeight, 0, 0, drawWidth, drawHeight)
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
