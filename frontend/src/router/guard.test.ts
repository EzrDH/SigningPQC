import { describe, expect, it } from 'vitest'
import { guard } from './guard'

describe('penjaga rute', () => {
  it('rute non-publik tanpa sesi diarahkan ke /masuk', () => {
    expect(guard({ path: '/profil', meta: {} }, false)).toBe('/masuk')
  })
  it('rute publik dengan sesi diarahkan ke beranda', () => {
    expect(guard({ path: '/masuk', meta: { public: true } }, true)).toBe('/')
  })
  it('selain itu boleh lewat', () => {
    expect(guard({ path: '/profil', meta: {} }, true)).toBe(true)
    expect(guard({ path: '/daftar', meta: { public: true } }, false)).toBe(true)
  })
})
