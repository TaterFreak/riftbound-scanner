import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { createStaticServer } from '../scripts/serve.js'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const testRoot = dirname(fileURLToPath(import.meta.url))

test('serveur statique : fichier existant renvoie 200 avec bon MIME', async () => {
  const server = createStaticServer(testRoot)

  await new Promise((resolve, reject) => {
    server.listen(0, () => {
      const { port } = server.address()
      const req = http.request(
        { hostname: 'localhost', port, path: '/fixtures/catalog-sample.json' },
        (res) => {
          let data = ''
          res.on('data', (chunk) => { data += chunk })
          res.on('end', () => {
            try {
              assert.equal(res.statusCode, 200)
              assert.equal(res.headers['content-type'], 'application/json; charset=utf-8')
              server.close(resolve)
            } catch (err) {
              server.close(() => reject(err))
            }
          })
        }
      )
      req.on('error', (err) => {
        server.close(() => reject(err))
      })
      req.end()
    })
  })
})

test('serveur statique : fichier absent renvoie 404', async () => {
  const server = createStaticServer(testRoot)

  await new Promise((resolve, reject) => {
    server.listen(0, () => {
      const { port } = server.address()
      const req = http.request(
        { hostname: 'localhost', port, path: '/fichier-inexistant.json' },
        (res) => {
          res.on('data', () => {})
          res.on('end', () => {
            try {
              assert.equal(res.statusCode, 404)
              server.close(resolve)
            } catch (err) {
              server.close(() => reject(err))
            }
          })
        }
      )
      req.on('error', (err) => {
        server.close(() => reject(err))
      })
      req.end()
    })
  })
})

test('serveur statique : URL malformée renvoie 400 sans tuer le process', async () => {
  const server = createStaticServer(testRoot)

  await new Promise((resolve, reject) => {
    server.listen(0, () => {
      const { port } = server.address()
      const req = http.request(
        { hostname: 'localhost', port, path: '/%' },
        (res) => {
          let data = ''
          res.on('data', (chunk) => { data += chunk })
          res.on('end', () => {
            try {
              assert.equal(res.statusCode, 400)
              // Vérifier que le serveur est toujours vivant
              const req2 = http.request(
                { hostname: 'localhost', port, path: '/fixtures/catalog-sample.json' },
                (res2) => {
                  res2.on('data', () => {})
                  res2.on('end', () => {
                    try {
                      assert.equal(res2.statusCode, 200)
                      server.close(resolve)
                    } catch (err) {
                      server.close(() => reject(err))
                    }
                  })
                }
              )
              req2.on('error', (err) => {
                server.close(() => reject(err))
              })
              req2.end()
            } catch (err) {
              server.close(() => reject(err))
            }
          })
        }
      )
      req.on('error', (err) => {
        server.close(() => reject(err))
      })
      req.end()
    })
  })
})

test('serveur statique : traversée de répertoire bloquée', async () => {
  const server = createStaticServer(testRoot)

  await new Promise((resolve, reject) => {
    server.listen(0, () => {
      const { port } = server.address()
      const req = http.request(
        { hostname: 'localhost', port, path: '/../package.json' },
        (res) => {
          res.on('data', () => {})
          res.on('end', () => {
            try {
              assert.equal(res.statusCode, 404)
              server.close(resolve)
            } catch (err) {
              server.close(() => reject(err))
            }
          })
        }
      )
      req.on('error', (err) => {
        server.close(() => reject(err))
      })
      req.end()
    })
  })
})
