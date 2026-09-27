// Serveur statique minimal pour le développement. Aucune dépendance.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const PORT = Number(process.env.PORT ?? 8080)
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
}

export function createStaticServer(root) {
  return createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname)
      const rel = normalize(path === '/' ? '/index.html' : path).replace(/^([/\\])+/, '')
      const body = await readFile(join(root, rel))
      res.writeHead(200, { 'content-type': TYPES[extname(rel)] ?? 'application/octet-stream' })
      res.end(body)
    } catch (err) {
      if (err.code === 'ERR_INVALID_URL' || err instanceof URIError) {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('Requête invalide')
      } else {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('Introuvable')
      }
    }
  })
}

// Démarrage automatique quand le fichier est lancé directement
const thisFile = fileURLToPath(import.meta.url)
if (process.argv[1] === thisFile) {
  createStaticServer(ROOT).listen(PORT, () => console.log(`http://localhost:${PORT}`))
}
