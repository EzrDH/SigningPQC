import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, configureApi } from './client'

const SERVER_DOWN = 'Server belum berjalan atau tidak bisa dihubungi. Jalankan backend, lalu coba lagi.'

function respond(status: number, body: unknown, extra: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...extra } })
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

  it.each([502, 503, 504])('%i tanpa body JSON berarti server tidak bisa dihubungi', async (status) => {
    fetchMock.mockResolvedValue(new Response('', { status }))
    await expect(api.get('/api/me')).rejects.toMatchObject({ status, message: SERVER_DOWN })
  })

  it('fetch gagal (jaringan) memberi pesan server tidak bisa dihubungi', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 0, message: SERVER_DOWN })
    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
  })

  it('token dikirim sebagai Bearer', async () => {
    fetchMock.mockResolvedValue(respond(200, { username: 'budi.uji' }))
    expect(await api.get<{ username: string }>('/api/me')).toEqual({ username: 'budi.uji' })
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-uji')
  })

  it('401 sesi (WWW-Authenticate: Bearer) di luar /api/auth memanggil onUnauthorized', async () => {
    fetchMock.mockImplementation(async () =>
      respond(401, { error: 'Sesi berakhir, silakan masuk lagi' }, { 'WWW-Authenticate': 'Bearer' }),
    )
    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError)
    expect(unauthorized).toBe(1)
    await expect(api.postJson('/api/auth/login', { login: 'a', password: 'b' })).rejects.toBeInstanceOf(ApiError)
    expect(unauthorized).toBe(1)
  })

  it('401 konfirmasi salah (tanpa WWW-Authenticate) tidak mengakhiri sesi', async () => {
    fetchMock.mockResolvedValue(respond(401, { error: 'Konfirmasi tidak cocok' }))
    await expect(api.postForm('/api/me/sign', new FormData())).rejects.toMatchObject({
      status: 401,
      message: 'Konfirmasi tidak cocok',
    })
    expect(unauthorized).toBe(0)
  })

  it('postForm mengembalikan Response mentah bila sukses', async () => {
    fetchMock.mockResolvedValue(new Response(new Uint8Array([37, 80, 68, 70]), { status: 200 }))
    const response = await api.postForm('/api/me/sign', new FormData())
    expect(response.status).toBe(200)
  })
})
