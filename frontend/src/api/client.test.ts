import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, configureApi } from './client'

function respond(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('klien API', () => {
  const fetchMock = vi.fn()
  let unauthorized = 0

  beforeEach(() => {
    fetchMock.mockReset()
    unauthorized = 0
    vi.stubGlobal('fetch', fetchMock)
    configureApi({ getToken: () => 'tok-uji', onUnauthorized: () => unauthorized++ })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('galat memakai pesan dari field error', async () => {
    fetchMock.mockResolvedValue(respond(400, { error: 'Berkas bukan PDF' }))
    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 400, message: 'Berkas bukan PDF' })
    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
  })

  it('galat tanpa body memakai pesan umum', async () => {
    fetchMock.mockResolvedValue(new Response('x', { status: 500 }))
    await expect(api.get('/api/me')).rejects.toMatchObject({ message: 'Terjadi kesalahan (500)' })
  })

  it('token dikirim sebagai Bearer', async () => {
    fetchMock.mockResolvedValue(respond(200, { username: 'budi.uji' }))
    expect(await api.get<{ username: string }>('/api/me')).toEqual({ username: 'budi.uji' })
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-uji')
  })

  it('401 di luar /api/auth memanggil onUnauthorized', async () => {
    fetchMock.mockResolvedValue(respond(401, { error: 'Sesi berakhir, silakan masuk lagi' }))
    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
    expect(unauthorized).toBe(1)
    await expect(api.postJson('/api/auth/login', { login: 'a', password: 'b' })).rejects.toBeInstanceOf(ApiError)
    expect(unauthorized).toBe(1)
  })

  it('postForm mengembalikan Response mentah bila sukses', async () => {
    fetchMock.mockResolvedValue(new Response(new Uint8Array([37, 80, 68, 70]), { status: 200 }))
    const response = await api.postForm('/api/me/sign', new FormData())
    expect(response.status).toBe(200)
  })
})
