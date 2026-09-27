// Accès caméra et découpe de la zone du viseur. Entrées-sorties pures.

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
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 } },
      audio: false
    })
  } catch (cause) {
    if (cause.name === 'NotAllowedError') {
      throw new CameraError("L'accès à la caméra a été refusé.")
    }
    throw new CameraError(`La caméra n'a pas pu démarrer : ${cause.message}`)
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
 */
export function grabViewfinder(videoElement, canvas, band) {
  const width = videoElement.videoWidth
  const height = videoElement.videoHeight
  if (!width || !height) return

  const sourceY = Math.round(height * band.top)
  const sourceHeight = Math.round(height * band.height)

  canvas.width = width
  canvas.height = sourceHeight

  const context = canvas.getContext('2d', { willReadFrequently: true })
  context.drawImage(videoElement, 0, sourceY, width, sourceHeight, 0, 0, width, sourceHeight)
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
