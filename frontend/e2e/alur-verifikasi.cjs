// Uji manual Task 14: verifikasi dokumen sah, dokumen diubah, dan PDF tanpa tanda tangan.
const { chromium } = require('playwright')
const BASE = 'http://127.0.0.1:5173/'
const SHOTS = process.argv[2]
const FILES = '/tmp/e2e/files'
;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2 })
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e)))
  const shot = (n) => page.screenshot({ path: `${SHOTS}/T-015-${n}.png` })
  await page.goto(BASE + '#/masuk')
  await page.getByLabel('Username atau email').fill('uji.e2e@contoh.test')
  await page.getByLabel('Password', { exact: true }).fill('password-uji-1')
  await page.getByRole('button', { name: 'Masuk' }).click()
  await page.waitForURL((u) => u.hash === '#/')
  for (const [file, label] of [['surat-uji-ttd.pdf', 'sah'], ['surat-uji-diubah.pdf', 'diubah'], ['surat-uji.pdf', 'tanpa-ttd']]) {
    await page.goto(BASE + '#/')
    await page.getByRole('link', { name: /Verifikasi dokumen/ }).click()
    await page.waitForURL(/unggah\/verifikasi/)
    await page.locator('input[type=file]').setInputFiles(`${FILES}/${file}`)
    await page.waitForURL(/#\/verifikasi/)
    await page.locator('.verdict h2').waitFor()
    await page.locator('.pdf-page canvas').waitFor()
    await page.waitForTimeout(400)
    const facts = await page.locator('.facts li').allTextContents()
    console.log(label, '|', await page.locator('.verdict h2').textContent(), '|', await page.locator('.verdict p').textContent(), '|', facts.join(' / '))
    await shot(`11-verifikasi-${label}`)
    if (label === 'sah') {
      await page.getByRole('button', { name: 'Lihat detail' }).click()
      await page.getByRole('tab', { name: /Tanda tangan 2 · ML-DSA/ }).click()
      await shot('12-detail-mldsa-ringkasan')
      await page.getByRole('tab', { name: 'Sertifikat', exact: true }).click()
      console.log('  sertifikat ML-DSA:', (await page.locator('.dlg .kv dd').allTextContents()).slice(5, 7).join(' | '))
      await shot('13-detail-mldsa-sertifikat')
      await page.getByRole('tab', { name: 'Rantai sertifikat' }).click()
      console.log('  rantai:', (await page.locator('.chain li').allTextContents()).join(' > '))
      await page.getByRole('tab', { name: 'Timestamp' }).click()
      console.log('  timestamp:', (await page.locator('.dlg .kv dd').allTextContents()).slice(1, 4).join(' | '))
      await shot('14-detail-mldsa-timestamp')
      await page.keyboard.press('Escape')
      await page.getByRole('dialog').waitFor({ state: 'detached' })
    }
  }
  console.log('tombol perpanjang tampil:', await page.getByRole('button', { name: 'Perpanjang keabsahan' }).count())
  console.log('galat halaman:', errors.length ? errors : 'tidak ada')
  await browser.close()
})().catch((e) => { console.error('GAGAL', e); process.exit(1) })
