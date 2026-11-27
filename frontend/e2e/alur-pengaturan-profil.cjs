// Uji manual Task 15: pengaturan, invisible, ganti metode ke passphrase, ganti folder, profil, keluar. Data sintetis.
const { chromium } = require('playwright')
const crypto = require('crypto')
const fs = require('fs')
const BASE = 'http://127.0.0.1:5173/'
const SHOTS = process.argv[2]
const NEWDIR = process.argv[3]
const FILES = '/tmp/e2e/files'
const user = { username: 'uji.lengkap3', email: 'uji.lengkap3@contoh.test', password: 'password-uji-2', passphrase: 'passphrase-uji-34' }
function base32(s) { const a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = '', out = []; for (const c of s.replace(/=+$/, '').toUpperCase()) bits += a.indexOf(c).toString(2).padStart(5, '0'); for (let i = 0; i + 8 <= bits.length; i += 8) out.push(parseInt(bits.slice(i, i + 8), 2)); return Buffer.from(out) }
function totp(secret, step) { const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(step)); const h = crypto.createHmac('sha1', base32(secret)).update(msg).digest(); const o = h[h.length - 1] & 15; return String(((h.readUInt32BE(o) & 0x7fffffff) % 1e6)).padStart(6, '0') }
const now = () => Math.floor(Date.now() / 30000)

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2 })
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e)))
  let secret = null
  page.on('response', async (r) => { if (r.url().endsWith('/api/auth/register/start') && r.ok()) secret = new URL((await r.json()).otpauthUri).searchParams.get('secret') })
  const shot = (n) => page.screenshot({ path: `${SHOTS}/T-015-${n}.png` })
  const sign = async (file) => {
    await page.goto(BASE + '#/unggah/tandatangani')
    await page.locator('input[type=file]').setInputFiles(`${FILES}/${file}`)
    await page.waitForURL(/#\/tandatangani/)
    await page.locator('.pdf-page canvas').waitFor()
    await page.getByRole('button', { name: 'Tandatangani' }).click()
    await page.getByRole('dialog').waitFor()
  }

  // Daftar akun baru (alur sudah diuji di Task 13).
  await page.goto(BASE + '#/daftar')
  await page.getByLabel('Username', { exact: true }).fill(user.username)
  await page.getByLabel('Email', { exact: true }).fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill(user.password)
  await page.getByRole('button', { name: 'Lanjut' }).click()
  await page.getByLabel('Passphrase', { exact: true }).fill(user.passphrase)
  await page.getByLabel('Ulangi passphrase').fill(user.passphrase)
  await page.getByRole('button', { name: 'Lanjut' }).click()
  await page.getByAltText('Kode QR untuk aplikasi authenticator').waitFor()
  let step = now()
  await page.getByLabel('Digit 1').focus()
  await page.keyboard.type(totp(secret, step))
  await page.waitForURL((u) => u.hash === '#/')

  // Pengaturan awal, lalu Invisible.
  await page.getByRole('link', { name: 'Pengaturan' }).click()
  await page.getByRole('heading', { name: 'Pengaturan' }).waitFor()
  await page.getByText('Belum ada spesimen').waitFor()
  await shot('15-pengaturan-awal')
  await page.getByLabel('Invisible', { exact: true }).check()
  await page.waitForFunction(() => !document.querySelector('.specprev'))
  await sign('surat-uji.pdf')
  while (now() <= step) await page.waitForTimeout(1000)
  step = now()
  await page.getByLabel('Digit 1').focus()
  await page.keyboard.type(totp(secret, step))
  await page.locator('.saved.on').waitFor({ timeout: 30000 })
  console.log('invisible tersimpan:', await page.locator('.saved code').textContent(), '| kotak spesimen:', await page.locator('.spec').count())
  await shot('16-tandatangani-invisible')

  // Ganti metode ke Passphrase: batal dulu, lalu passphrase salah, lalu benar.
  await page.goto(BASE + '#/pengaturan')
  await page.getByLabel('Visible', { exact: true }).check()
  await page.locator('.specprev').waitFor()
  await page.getByLabel('Passphrase', { exact: true }).click()
  await page.getByRole('dialog').waitFor()
  await page.getByRole('button', { name: 'Batal' }).click()
  console.log('setelah batal, TOTP tetap terpilih:', await page.getByLabel('TOTP').isChecked(), '| Passphrase:', await page.getByLabel('Passphrase', { exact: true }).isChecked())
  await page.getByLabel('Passphrase', { exact: true }).click()
  await page.getByRole('dialog').getByLabel('Passphrase').fill('passphrase-salah-1')
  await page.getByRole('button', { name: 'Simpan' }).click()
  await page.getByRole('dialog').getByRole('alert').waitFor()
  console.log('passphrase salah:', await page.getByRole('dialog').getByRole('alert').textContent())
  await shot('17-ganti-metode-salah')
  await page.getByRole('dialog').getByLabel('Passphrase').fill(user.passphrase)
  await page.getByRole('button', { name: 'Simpan' }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  console.log('metode sekarang Passphrase:', await page.getByLabel('Passphrase', { exact: true }).isChecked())

  // Ganti folder keluaran: relatif ditolak, absolut diterima.
  await page.getByRole('button', { name: 'Ubah' }).click()
  await page.getByLabel('Folder keluaran').fill('folder-relatif')
  await page.getByRole('button', { name: 'Simpan' }).click()
  await page.locator('.box .err').waitFor()
  console.log('folder relatif:', await page.locator('.box .err').textContent())
  await page.getByLabel('Folder keluaran').fill(NEWDIR)
  await page.getByRole('button', { name: 'Simpan' }).click()
  await page.locator('.path code').waitFor()
  console.log('folder baru:', await page.locator('.path code').textContent())
  await shot('18-pengaturan-passphrase-folder')

  // Tanda tangan Visible dengan passphrase ke folder baru.
  await sign('surat-uji.pdf')
  await page.getByRole('dialog').getByLabel('Passphrase').fill(user.passphrase)
  await page.getByRole('dialog').getByRole('button', { name: 'Tandatangani' }).click()
  await page.locator('.saved.on').waitFor({ timeout: 30000 })
  console.log('passphrase tersimpan:', await page.locator('.saved code').textContent())

  // Profil dan Keluar.
  await page.getByRole('link', { name: 'Profil' }).click()
  await page.locator('.certs .box').nth(1).waitFor()
  console.log('profil:', (await page.locator('.certs h3').allTextContents()).join(', '), '|', await page.locator('.av').textContent())
  await shot('19-profil')
  await page.getByRole('button', { name: 'Keluar' }).click()
  await page.waitForURL(/#\/masuk/)
  await page.goto(BASE + '#/profil')
  await page.waitForURL(/#\/masuk/)
  console.log('setelah keluar, halaman terlindungi kembali ke Masuk; token tersisa:', await page.evaluate(() => sessionStorage.getItem('tte.token')))
  await page.goto(BASE + '#/')
  await page.getByLabel('Username atau email').fill(user.username)
  await page.getByLabel('Password', { exact: true }).fill(user.password)
  await page.getByRole('button', { name: 'Masuk' }).click()
  await page.waitForURL((u) => u.hash === '#/')
  await page.getByRole('button', { name: 'Lihat riwayat dokumen' }).click()
  await page.locator('.hist div').first().waitFor()
  console.log('riwayat:', (await page.locator('.hist div').allTextContents()).length, 'entri')
  await shot('20-riwayat-setelah-masuk')
  console.log('galat halaman:', errors.length ? errors : 'tidak ada')
  await browser.close()
})().catch((e) => { console.error('GAGAL', e); process.exit(1) })
