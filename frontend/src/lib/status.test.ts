import { describe, expect, it } from 'vitest'
import { historyStatus } from './status'

describe('historyStatus', () => {
  it('memetakan kode status riwayat ke label dan nada', () => {
    expect(historyStatus('SAH')).toEqual({ label: 'Sah', tone: 'ok' })
    expect(historyStatus('TIDAK_SAH')).toEqual({ label: 'Tidak sah', tone: 'bad' })
    expect(historyStatus('TIDAK_DAPAT_DIBUKTIKAN')).toEqual({ label: 'Tidak dapat dibuktikan', tone: 'warn' })
    expect(historyStatus('TANPA_TANDA_TANGAN')).toEqual({ label: 'Tanpa tanda tangan', tone: 'none' })
    expect(historyStatus('TIDAK_DIPERIKSA')).toEqual({ label: 'Tidak diperiksa', tone: 'none' })
  })
  it('kode tak dikenal ditampilkan apa adanya dengan nada netral', () => {
    expect(historyStatus('LAINNYA')).toEqual({ label: 'LAINNYA', tone: 'none' })
  })
})

import { describeResult } from './status'
import type { LayerReport, OverallStatus, VerifyResponse } from '../api/types'

function layer(code: string, status: OverallStatus = 'SAH'): LayerReport {
  return {
    revision: 2,
    kind: 'TANDA_TANGAN',
    pades: 'B-T',
    algorithm: 'RSA (SHA-256)',
    subject: 'C=ID,O=SigningPQC Uji,CN=budi.uji',
    time: '2026-11-26T01:20:00Z',
    code,
    status,
    detail: '',
  }
}

function response(status: OverallStatus | null, opts: { codes?: string[]; warnings?: string[]; renew?: boolean } = {}): VerifyResponse {
  const signed = status !== null
  return {
    fileName: 'surat-ttd.pdf',
    allValid: status === 'SAH',
    hybridComplete: status === 'SAH',
    summary: '',
    signatures: signed
      ? [
          {
            order: 1,
            fieldName: 'budi.uji',
            signatureAlgorithm: 'RSA (SHA-256)',
            signerSubject: 'C=ID,O=SigningPQC Uji,CN=budi.uji',
            claimedSigningTime: '2026-11-26T01:20:00Z',
            coversWholeDocument: false,
            valid: status !== 'TIDAK_SAH',
            message: '',
            signatureTimestamp: null,
            detail: null,
          },
        ]
      : [],
    validation: signed
      ? {
          status,
          hybridComplete: status === 'SAH',
          validationTime: '2026-11-26T02:00:00Z',
          layers: (opts.codes ?? ['OK']).map((c) => layer(c, c === 'OK' || c === 'DICABUT_SETELAH_DITANDATANGANI' ? 'SAH' : status)),
          protectionUntil: '2028-11-24T03:00:00Z',
          protectionLimitReason: null,
          renewalRecommended: opts.renew ?? false,
          warnings: opts.warnings ?? [],
        }
      : null,
  }
}

describe('describeResult', () => {
  it('sah tanpa peringatan', () => {
    const d = describeResult(response('SAH'))
    expect(d.tone).toBe('ok')
    expect(d.title).toBe('Dokumen sah')
    expect(d.subtitle).toBe('Tanda tangan asli, isi tidak berubah')
    expect(d.canRenew).toBe(false)
    expect(d.renewMessage).toBeNull()
    expect(d.until).toBe('24 November 2028')
    expect(d.points.map((p) => p.text)).toEqual([
      'Ditandatangani oleh budi.uji',
      '26 November 2026, 08.20 WIB',
      'Isi tidak berubah sejak ditandatangani',
      'Tanda tangan tahan komputer kuantum (ML-DSA)',
    ])
  })

  it('sah dengan sertifikat dicabut setelah ditandatangani memberi catatan', () => {
    const d = describeResult(
      response('SAH', { codes: ['DICABUT_SETELAH_DITANDATANGANI'], warnings: ['DICABUT_SETELAH_DITANDATANGANI'] }),
    )
    expect(d.tone).toBe('ok')
    expect(d.title).toBe('Dokumen sah')
    expect(d.subtitle).toBe('Sertifikat dicabut setelah ditandatangani')
  })

  it('tidak dapat dibuktikan karena TSA kedaluwarsa tanpa pembaruan', () => {
    const d = describeResult(response('TIDAK_DAPAT_DIBUKTIKAN', { codes: ['TSA_KEDALUWARSA_TANPA_PEMBARUAN'] }))
    expect(d.tone).toBe('warn')
    expect(d.title).toBe('Keabsahan tidak dapat dibuktikan')
    expect(d.canRenew).toBe(false)
    expect(d.renewMessage).toBe('Masa keabsahan sudah berakhir dan tidak bisa diperpanjang lagi.')
    expect(d.until).toBeNull()
  })

  it('tidak sah karena isi berubah', () => {
    const d = describeResult(response('TIDAK_SAH', { codes: ['DOKUMEN_BERUBAH'] }))
    expect(d.tone).toBe('bad')
    expect(d.title).toBe('Dokumen tidak sah')
    expect(d.subtitle).toBe('Isi dokumen berubah setelah ditandatangani')
    expect(d.points.find((p) => !p.ok)?.text).toBe('Isi dokumen berubah setelah ditandatangani')
    expect(d.canRenew).toBe(false)
  })

  it('tanpa tanda tangan', () => {
    const d = describeResult(response(null))
    expect(d.tone).toBe('none')
    expect(d.title).toBe('Dokumen ini tidak memiliki tanda tangan elektronik')
    expect(d.points).toEqual([])
    expect(d.canRenew).toBe(false)
  })

  it('perpanjangan disarankan mengikuti renewalRecommended', () => {
    expect(describeResult(response('SAH', { renew: true })).canRenew).toBe(true)
  })
})
