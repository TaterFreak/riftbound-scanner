// Génère icon-192.png et icon-512.png : carrés unis à la couleur du thème.
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'

const COLOR = [0x10, 0x10, 0x14]

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

function crc32(buffer) {
  let c = 0xffffffff
  for (const byte of buffer) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

function png(size) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // profondeur
  ihdr[9] = 2 // couleur vraie RVB
  const row = Buffer.concat([
    Buffer.from([0]), // filtre « none »
    Buffer.concat(Array.from({ length: size }, () => Buffer.from(COLOR)))
  ])
  const raw = Buffer.concat(Array.from({ length: size }, () => row))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0))
  ])
}

for (const size of [192, 512]) {
  const name = `icon-${size}.png`
  writeFileSync(new URL(`../${name}`, import.meta.url), png(size))
  console.log(`${name} écrit`)
}
