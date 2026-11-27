import { describe, expect, it } from 'vitest'
import { activityResult, authorityName, authorityPath, validity } from './pki'

describe('authorityName', () => {
  it('memberi nama CA dan TSA uji dalam bahasa pengguna', () => {
    expect(authorityName('rsa-root')).toBe('CA root RSA')
    expect(authorityName('rsa-intermediate')).toBe('CA penerbit RSA')
    expect(authorityName('mldsa-root')).toBe('CA root ML-DSA')
    expect(authorityName('mldsa-intermediate')).toBe('CA penerbit ML-DSA')
    expect(authorityName('tsa-rsa-g1')).toBe('TSA RSA generasi 1')
    expect(authorityName('tsa-rsa-g2')).toBe('TSA RSA generasi 2')
    expect(authorityName('tsa-mldsa')).toBe('TSA ML-DSA')
    expect(authorityName('lain')).toBe('lain')
  })
})

describe('authorityPath', () => {
  it('menunjuk berkas sertifikat CA, CRL, dan sertifikat TSA', () => {
    expect(authorityPath('rsa-intermediate', 'crt')).toBe('/api/pki/ca/rsa-intermediate.crt')
    expect(authorityPath('mldsa-root', 'pem')).toBe('/api/pki/ca/mldsa-root.pem')
    expect(authorityPath('rsa-root', 'crl')).toBe('/api/pki/crl/rsa-root.crl')
    expect(authorityPath('tsa-rsa-g2', 'crt')).toBe('/api/pki/tsa/rsa-g2.crt')
  })
})

describe('validity', () => {
  const cert = { notBefore: '2026-01-01T00:00:00Z', notAfter: '2027-01-01T00:00:00Z' }
  it('berlaku di dalam rentang', () => {
    expect(validity(cert, new Date('2026-11-27T00:00:00Z'))).toEqual({ label: 'Berlaku', tone: 'ok' })
  })
  it('kedaluwarsa setelah notAfter', () => {
    expect(validity(cert, new Date('2027-01-02T00:00:00Z'))).toEqual({ label: 'Kedaluwarsa', tone: 'bad' })
  })
  it('belum berlaku sebelum notBefore', () => {
    expect(validity(cert, new Date('2025-12-31T00:00:00Z'))).toEqual({ label: 'Belum berlaku', tone: 'warn' })
  })
  it('dicabut mengalahkan masa berlaku', () => {
    expect(validity(cert, new Date('2026-11-27T00:00:00Z'), true)).toEqual({ label: 'Dicabut', tone: 'bad' })
  })
})

describe('activityResult', () => {
  it('berhasil dan gagal', () => {
    expect(activityResult('BERHASIL')).toEqual({ label: 'Berhasil', tone: 'ok' })
    expect(activityResult('GAGAL')).toEqual({ label: 'Gagal', tone: 'bad' })
  })
  it('hasil verifikasi memakai label riwayat', () => {
    expect(activityResult('SAH')).toEqual({ label: 'Sah', tone: 'ok' })
    expect(activityResult('TIDAK_DAPAT_DIBUKTIKAN')).toEqual({ label: 'Tidak dapat dibuktikan', tone: 'warn' })
  })
})
