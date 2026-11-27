import { describe, expect, it } from 'vitest'
import { crlLine, documentRows, labelWithOid, tsaPolicy } from './evidence'
import type { LayerReport, ValidationReport } from '../api/types'

describe('labelWithOid', () => {
  it('menampilkan nama beserta OID', () => {
    expect(labelWithOid('RSA', '1.2.840.113549.1.1.1')).toBe('RSA (OID 1.2.840.113549.1.1.1)')
  })
  it('OID tanpa nama dikenal ditampilkan sekali', () => {
    expect(labelWithOid('1.2.3.4', '1.2.3.4')).toBe('OID 1.2.3.4')
  })
  it('tanpa OID hanya nama', () => {
    expect(labelWithOid('SHA-256', null)).toBe('SHA-256')
  })
})

describe('tsaPolicy', () => {
  it('mengenali OID kebijakan TSA uji', () => {
    expect(tsaPolicy('1.3.6.1.4.1.32473.1.1')).toBe('Kebijakan TSA uji (OID 1.3.6.1.4.1.32473.1.1)')
  })
  it('kosong menjadi tanda strip', () => {
    expect(tsaPolicy(null)).toBe('-')
  })
})

describe('crlLine', () => {
  it('meringkas CRL beserta sumbernya', () => {
    const line = crlLine({
      issuer: 'CN=CA Antara Uji RSA,O=SigningPQC Uji,C=ID',
      crlNumber: '3',
      thisUpdate: '2026-11-27T03:00:00Z',
      nextUpdate: '2026-12-04T03:00:00Z',
      source: 'DSS dokumen',
      listed: false,
      revokedAt: null,
    })
    expect(line).toBe(
      'CRL nomor 3 dari CA Antara Uji RSA, terbit 27 November 2026, 10.00 WIB, berlaku s.d. 4 Desember 2026, 10.00 WIB. Sumber: DSS dokumen. Sertifikat tidak tercantum.',
    )
  })
  it('menyebut waktu pencabutan bila tercantum', () => {
    const line = crlLine({
      issuer: 'CN=CA Antara Uji RSA',
      crlNumber: null,
      thisUpdate: '2026-11-27T03:00:00Z',
      nextUpdate: null,
      source: 'CRL lokal PKI uji',
      listed: true,
      revokedAt: '2026-11-27T02:00:00Z',
    })
    expect(line).toContain('CRL dari CA Antara Uji RSA')
    expect(line).toContain('Sertifikat dicabut pada 27 November 2026, 09.00 WIB.')
  })
})

function layer(revision: number, kind: LayerReport['kind'], pades: string): LayerReport {
  return { revision, kind, pades, algorithm: '-', subject: '-', time: null, code: 'OK', status: 'SAH', detail: 'ok', signatureTimestamp: null }
}

describe('documentRows', () => {
  it('menambahkan revisi 1 sebagai dokumen asli lalu tiap lapisan berurutan', () => {
    const validation = {
      layers: [layer(2, 'TANDA_TANGAN', 'B-T'), layer(3, 'TANDA_TANGAN', 'B-T'), layer(4, 'DATA_VALIDASI', 'LT'), layer(5, 'DOCUMENT_TIMESTAMP', 'LTA')],
    } as ValidationReport
    const rows = documentRows(validation, ['RSA (SHA-256)', 'ML-DSA-65'])
    expect(rows.map((r) => r.revision)).toEqual([1, 2, 3, 4, 5])
    expect(rows[0].content).toBe('Dokumen asli')
    expect(rows[0].status).toBe('-')
    expect(rows[1].content).toBe('Tanda tangan RSA (SHA-256)')
    expect(rows[2].content).toBe('Tanda tangan ML-DSA-65')
    expect(rows[3].content).toBe('Data validasi (DSS)')
    expect(rows[4].content).toBe('Document timestamp')
    expect(rows[4].level).toBe('LTA')
    expect(rows[4].status).toBe('Sah')
  })
  it('keterangan yang sama dengan status tidak diulang', () => {
    const validation = { layers: [{ ...layer(2, 'TANDA_TANGAN', 'B-T'), detail: 'Sah' }] } as ValidationReport
    expect(documentRows(validation, ['RSA (SHA-256)'])[1].detail).toBe('')
  })
})
