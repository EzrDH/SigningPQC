// Uji manual Task 13: daftar, tanda tangan Visible dengan spesimen, kode salah. Data sintetis saja.
const { chromium } = require('playwright')
const crypto = require('crypto')
const fs = require('fs')

const BASE = 'http://127.0.0.1:5173/'
const SHOTS = process.argv[2]
const FILES = '/tmp/e2e/files'
const user = { username: 'uji.e2e', email: 'uji.e2e@contoh.test', password: 'password-uji-1', passphrase: 'passphrase-uji-12' }

function base32(s) {
  const a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = '', out = []
  for (const c of s.replace(/=+$/, '').toUpperCase()) bits += a.indexOf(c).toString(2).padStart(5, '0')
  for (let i = 0; i + 8 <= bits.length; i += 8) out.push(parseInt(bits.slice(i, i + 8), 2))
  return Buffer.from(out)
}
function totp(secret, step) {
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(step))
  const h = crypto.createHmac('sha1', base32(secret)).update(msg).digest()
  const o = h[h.length - 1] & 15
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1e6)).padStart(6, '0')
}
const now = () => Math.floor(Date.now() / 30000)
async function nextStepAfter(used) { while (now() <= used) await new Promise((r) => setTimeout(r, 1000)); return now() }

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2 })
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e)))
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
  let secret = null
  page.on('response', async (r) => {
    if (r.url().endsWith('/api/auth/register/start') && r.ok()) secret = new URL((await r.json()).otpauthUri).searchParams.get('secret')
  })
  const shot = (n) => page.screenshot({ path: `${SHOTS}/T-015-${n}.png` })

  await page.goto(BASE)
  await page.waitForURL(/#\/masuk/)
  await shot('01-masuk')
  await page.getByRole('link', { name: 'Daftar' }).click()
  await page.waitForURL(/#\/daftar/)
  await page.getByLabel('Username', { exact: true }).fill(user.username)
  await page.getByLabel('Email', { exact: true }).fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('pendek')
  await page.getByRole('button', { name: 'Lanjut' }).click()
  console.log('validasi password:', await page.getByRole('alert').textContent())
  await page.getByLabel('Password', { exact: true }).fill(user.password)
  await shot('02-daftar-1')
  await page.getByRole('button', { name: 'Lanjut' }).click()
  await page.getByLabel('Passphrase', { exact: true }).fill(user.passphrase)
  await page.getByLabel('Ulangi passphrase').fill(user.passphrase + 'x')
  await page.getByRole('button', { name: 'Lanjut' }).click()
  console.log('validasi ulangan:', await page.getByRole('alert').textContent())
  await page.getByLabel('Ulangi passphrase').fill(user.passphrase)
  await shot('03-daftar-2')
  await page.getByRole('button', { name: 'Lanjut' }).click()
  await page.getByAltText('Kode QR untuk aplikasi authenticator').waitFor()
  await shot('04-daftar-3-qr')
  let step = now()
  await page.getByLabel('Digit 1').focus()
  await page.keyboard.type(totp(secret, step))
  await page.waitForURL((u) => u.hash === '#/')
  const usedAtRegistration = step
  await shot('05-beranda')

  // Spesimen sintetis lewat API (layar Pengaturan dibuat di Task 15).
  const specimenStatus = await page.evaluate(async () => {
    const c = document.createElement('canvas'); c.width = 480; c.height = 180
    const g = c.getContext('2d'); g.strokeStyle = '#0f3d91'; g.lineWidth = 7; g.lineCap = 'round'
    g.beginPath(); g.moveTo(20, 130); g.bezierCurveTo(90, 10, 140, 170, 210, 80); g.bezierCurveTo(260, 20, 300, 160, 360, 70); g.lineTo(460, 60); g.stroke()
    const blob = await new Promise((r) => c.toBlob(r, 'image/png'))
    const f = new FormData(); f.append('file', blob, 'spesimen.png')
    const res = await fetch('/api/me/specimen', { method: 'PUT', body: f, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('tte.token') } })
    return res.status
  })
  console.log('unggah spesimen:', specimenStatus)

  await page.getByRole('button', { name: 'Lihat riwayat dokumen' }).click()
  await page.getByText(/Belum ada riwayat/).waitFor()

  for (const [name, label] of [['surat-uji.pdf', 'a4'], ['pindai-lanskap.pdf', 'rotasi90']]) {
    await page.goto(BASE + '#/')
    await page.getByRole('link', { name: /Tandatangani dokumen/ }).click()
    await page.waitForURL(/unggah\/tandatangani/)
    if (label === 'a4') await shot('06-unggah')
    await page.locator('input[type=file]').setInputFiles(`${FILES}/${name}`)
    await page.waitForURL(/#\/tandatangani/)
    const spec = page.locator('.spec')
    await spec.waitFor()
    await page.waitForFunction(() => document.querySelector('.spec img')?.complete)
    const before = await spec.boundingBox()
    // Seret ke kiri atas 160 x 120 piksel.
    await page.mouse.move(before.x + 40, before.y + 20)
    await page.mouse.down()
    await page.mouse.move(before.x - 120, before.y - 100, { steps: 8 })
    await page.mouse.up()
    const after = await spec.boundingBox()
    const canvasBox = await page.locator('.pdf-page canvas').boundingBox()
    fs.writeFileSync(`/tmp/e2e/box-${label}.json`, JSON.stringify({ after, canvasBox }))
    await shot(`07-tandatangani-${label}`)
    await page.getByRole('button', { name: 'Tandatangani' }).click()
    await page.getByRole('dialog').waitFor()
    if (label === 'a4') {
      await page.getByLabel('Digit 1').focus()
      await page.keyboard.type('000000')
      await page.getByRole('dialog').getByRole('alert').waitFor()
      console.log('kode salah:', await page.getByRole('dialog').getByRole('alert').textContent())
      await shot('08-kode-salah')
    }
    step = await nextStepAfter(step)
    await page.getByLabel('Digit 1').focus()
    await page.keyboard.type(totp(secret, step))
    await page.locator('.saved.on').waitFor({ timeout: 30000 })
    console.log(`tersimpan (${label}):`, (await page.locator('.saved code').textContent()))
    await shot(`09-berhasil-${label}`)
  }

  await page.goto(BASE + '#/')
  await page.getByRole('button', { name: 'Lihat riwayat dokumen' }).click()
  await page.locator('.hist div').first().waitFor()
  await shot('10-riwayat')
  console.log('galat konsol:', errors.length ? errors : 'tidak ada')
  await browser.close()
})().catch((e) => { console.error('GAGAL', e); process.exit(1) })
