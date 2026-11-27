import { describe, expect, it } from 'vitest'
import { cekAkun, cekPassphrase } from './validasi'

describe('cekAkun', () => {
  it('menerima data yang sah', () => {
    expect(cekAkun('budi.uji', 'budi@contoh.go.id', 'rahasia12')).toBeNull()
  })
  it('username diubah ke huruf kecil sebelum dicek', () => {
    expect(cekAkun('Budi.Uji', 'budi@contoh.go.id', 'rahasia12')).toBeNull()
  })
  it('menolak username terlalu pendek atau berkarakter lain', () => {
    expect(cekAkun('ab', 'a@b', 'rahasia12')).toBe('Username 3 sampai 32 karakter: huruf, angka, titik, minus, atau garis bawah')
    expect(cekAkun('budi uji', 'a@b', 'rahasia12')).not.toBeNull()
  })
  it('menolak email tanpa tepat satu @', () => {
    expect(cekAkun('budi', 'budi.contoh', 'rahasia12')).toBe('Email tidak sah')
    expect(cekAkun('budi', 'a@b@c', 'rahasia12')).toBe('Email tidak sah')
  })
  it('menolak password kurang dari 8 karakter', () => {
    expect(cekAkun('budi', 'a@b', '1234567')).toBe('Password minimal 8 karakter')
  })
})

describe('cekPassphrase', () => {
  it('menolak kurang dari 12 karakter', () => {
    expect(cekPassphrase('12345678901', '12345678901')).toBe('Passphrase minimal 12 karakter')
  })
  it('menolak bila ulangan berbeda', () => {
    expect(cekPassphrase('passphrase-uji-1', 'passphrase-uji-2')).toBe('Passphrase dan ulangannya tidak sama')
  })
  it('menerima passphrase yang sama dan cukup panjang', () => {
    expect(cekPassphrase('passphrase-uji-1', 'passphrase-uji-1')).toBeNull()
  })
})
