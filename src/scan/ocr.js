// Adaptateur Tesseract.js. Entrées-sorties pures : la machine d'état ne connaît que
// le texte renvoyé par read(), ce qui permet de la tester avec un faux moteur.

const TESSERACT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js'

// Alphabet volontairement réduit au strict nécessaire du code de collection :
// c'est le second levier de fiabilité, après la réduction de la zone analysée.
const WHITELIST = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/*'

// Modes de l'unique worker : le code en bas de carte, ou le texte de règles complet.
const MODE_CODE = { tessedit_char_whitelist: WHITELIST, tessedit_pageseg_mode: '7' }
const MODE_TEXTE = { tessedit_char_whitelist: '', tessedit_pageseg_mode: '3' }

async function loadTesseract() {
  if (globalThis.Tesseract) return globalThis.Tesseract
  await new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = TESSERACT_URL
    script.onload = resolve
    script.onerror = () => reject(new Error("Tesseract.js n'a pas pu être chargé."))
    document.head.append(script)
  })
  return globalThis.Tesseract
}

export async function createTesseractOcr() {
  const Tesseract = await loadTesseract()
  const worker = await Tesseract.createWorker('eng')
  await worker.setParameters(MODE_CODE)
  let mode = 'code'

  async function useMode(next) {
    if (mode === next) return
    await worker.setParameters(next === 'code' ? MODE_CODE : MODE_TEXTE)
    mode = next
  }

  return {
    /** Lecture du code : alphabet réduit, une seule ligne. C'est le chemin chaud. */
    async read(canvas) {
      await useMode('code')
      const { data } = await worker.recognize(canvas)
      return data.text ?? ''
    },
    /** Lecture du texte de règles, alphabet complet. Ponctuelle et coûteuse. */
    async readText(canvas) {
      await useMode('texte')
      const { data } = await worker.recognize(canvas)
      await useMode('code')
      return data.text ?? ''
    },
    terminate: () => worker.terminate()
  }
}
