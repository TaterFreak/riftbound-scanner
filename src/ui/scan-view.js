// Écran de scan : caméra, boucle d'analyse, réglages collants, écrans de décision.
import { startCamera, grabViewfinder, grabFull, CameraError } from '../scan/camera.js'
import { createTesseractOcr } from '../scan/ocr.js'
import { addScan, incrementEntry } from '../collection/entries.js'
import { parseCollectorCode } from '../recognize/collector-code.js'
import { detectLanguage } from '../recognize/language.js'

// Aligné sur .viseur dans styles.css (top: 78%, height: 10%). Modifier les deux ensemble :
// si l'un dérive, l'utilisateur vise une zone qui n'est pas celle analysée par l'OCR.
const BAND = { top: 0.78, height: 0.1 } // bande basse, là où le code est imprimé
const INTERVAL = 400

export function createScanView({ root, machine, getState, setState, onStatus }) {
  const video = root.querySelector('#flux')
  const canvas = root.querySelector('#tampon')
  const dialogue = root.querySelector('#dialogue')
  const boutonFinition = root.querySelector('#reglage-finition')
  const boutonLangue = root.querySelector('#reglage-langue')

  let camera = null
  let ocr = null
  let timer = null
  let busy = false

  const beep = () => {
    const context = new AudioContext()
    const oscillator = context.createOscillator()
    oscillator.frequency.value = 880
    oscillator.connect(context.destination)
    oscillator.start()
    oscillator.stop(context.currentTime + 0.06)
  }

  function commit(card, rawCode) {
    const { entries, settings } = getState()
    const { entries: next } = addScan(entries, {
      card,
      rawCode,
      finish: settings.finish,
      language: settings.language,
      condition: settings.condition,
      scannedAt: new Date().toISOString()
    })
    setState({ entries: next })
    beep()
  }

  function closeDialogue() {
    dialogue.hidden = true
    dialogue.replaceChildren()
    machine.reset()
  }

  function ask(titre, boutons) {
    dialogue.replaceChildren()
    const h = document.createElement('p')
    h.textContent = titre
    dialogue.append(h)
    for (const [label, action] of boutons) {
      const bouton = document.createElement('button')
      bouton.type = 'button'
      bouton.textContent = label
      bouton.addEventListener('click', () => {
        action()
        closeDialogue()
      })
      dialogue.append(bouton)
    }
    dialogue.hidden = false
  }

  function handle(event) {
    if (!event) return

    if (event.type === 'accept') {
      commit(event.card, event.code)
      onStatus(`${event.card.name} ajoutée`)
      return
    }

    if (event.type === 'duplicate') {
      const { entries } = getState()
      const quantity = entries[event.index].quantity
      onStatus(`${event.card.name} déjà scannée ×${quantity}.`)
      ask(`${event.card.name} — déjà scannée ×${quantity}. Ajouter un exemplaire ?`, [
        [
          'Ajouter',
          () => {
            setState({ entries: incrementEntry(getState().entries, event.index) })
            onStatus(`${event.card.name} : quantité portée à ${quantity + 1}.`)
          }
        ],
        ['Ignorer', () => onStatus(`${event.card.name} : doublon ignoré.`)]
      ])
      return
    }

    if (event.type === 'ambiguous') {
      onStatus('Plusieurs cartes correspondent à ce code : choisis laquelle scanner.')
      ask(
        'Ce code correspond à plusieurs cartes. Laquelle ?',
        event.cards.map((card) => [
          card.name,
          () => {
            commit(card, event.code)
            onStatus(`${card.name} ajoutée`)
          }
        ])
      )
      return
    }

    if (event.type === 'unknown') {
      onStatus(`Code ${event.code} absent du catalogue : choisis une action.`)
      const choix = event.candidates.map((card) => [
        card.name,
        () => {
          commit(card, event.code)
          onStatus(`${card.name} ajoutée`)
        }
      ])
      ask(`Code ${event.code} absent du catalogue.`, [
        ...choix,
        [
          'Conserver tel quel',
          () => {
            commit(null, event.code)
            onStatus(`Code ${event.code} conservé comme carte inconnue.`)
          }
        ],
        ['Ignorer', () => onStatus(`Code ${event.code} ignoré.`)]
      ])
    }
  }

  async function tick() {
    if (busy || !dialogue.hidden) return
    busy = true
    try {
      grabViewfinder(video, canvas, BAND)
      const texte = await ocr.read(canvas)
      const { entries, settings } = getState()
      handle(machine.onFrame(texte, { ...settings, entries }))
    } finally {
      busy = false
    }
  }

  boutonFinition.addEventListener('click', () => {
    const { settings } = getState()
    const finish = settings.finish === 'normal' ? 'metal' : 'normal'
    setState({ settings: { ...settings, finish } })
    boutonFinition.textContent = finish === 'metal' ? 'Metal' : 'Normale'
    boutonFinition.setAttribute('aria-pressed', String(finish === 'metal'))
  })

  boutonLangue.addEventListener('click', () => {
    const { settings } = getState()
    const language = settings.language === 'en' ? 'fr' : 'en'
    setState({ settings: { ...settings, language } })
    boutonLangue.textContent = language.toUpperCase()
  })

  // Détection de langue : une passe d'OCR à alphabet complet sur la carte entière.
  // Trop coûteuse pour tourner à chaque trame, elle se déclenche à la demande, une
  // fois par paquet, et ne fait que positionner le réglage de session.
  root.querySelector('#detecter-langue').addEventListener('click', async () => {
    if (!ocr) {
      onStatus('La reconnaissance n’est pas encore prête.')
      return
    }
    onStatus('Lecture du texte de la carte…')
    grabFull(video, canvas)
    const language = detectLanguage(await ocr.readText(canvas))
    if (!language) {
      onStatus('Langue indéterminée. Règle-la à la main.')
      return
    }
    const { settings } = getState()
    setState({ settings: { ...settings, language } })
    boutonLangue.textContent = language.toUpperCase()
    onStatus(`Langue détectée : ${language.toUpperCase()}.`)
  })

  root.querySelector('#saisie-manuelle').addEventListener('submit', (e) => {
    e.preventDefault()
    const champ = root.querySelector('#code-manuel')
    const code = parseCollectorCode(champ.value)
    if (!code) {
      onStatus('Code non reconnu.')
      return
    }
    const { entries, settings } = getState()
    handle(machine.decide(code, { ...settings, entries }))
    champ.value = ''
  })

  return {
    async start() {
      try {
        camera = await startCamera(video)
      } catch (error) {
        if (error instanceof CameraError) {
          onStatus(`${error.message} La saisie manuelle reste disponible.`)
          return
        }
        throw error
      }
      onStatus('Chargement de la reconnaissance…')
      ocr = await createTesseractOcr()
      onStatus('Prêt. Vise le code en bas de la carte.')
      timer = setInterval(tick, INTERVAL)
    },
    stop() {
      clearInterval(timer)
      camera?.stop()
      ocr?.terminate()
      camera = null
      ocr = null
    }
  }
}
