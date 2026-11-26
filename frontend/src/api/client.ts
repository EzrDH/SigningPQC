/** Klien fetch untuk backend: token Bearer, galat JSON {"error": "..."} menjadi ApiError. */
export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

interface ApiConfig {
  getToken: () => string | null
  onUnauthorized: () => void
}

export const SERVER_DOWN = 'Server belum berjalan atau tidak bisa dihubungi. Jalankan backend, lalu coba lagi.'

let config: ApiConfig = { getToken: () => null, onUnauthorized: () => {} }

export function configureApi(next: ApiConfig) {
  config = next
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = config.getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError(0, SERVER_DOWN)
  }
  if (response.ok) return response
  // 502/503/504 tanpa body JSON datang dari proxy (Vite atau lainnya) saat backend tidak berjalan.
  let message = [502, 503, 504].includes(response.status) ? SERVER_DOWN : `Terjadi kesalahan (${response.status})`
  try {
    const body = await response.json()
    if (body && typeof body.error === 'string' && body.error) message = body.error
  } catch {
    // body bukan JSON
  }
  // Hanya 401 karena sesi (ditandai WWW-Authenticate: Bearer, RFC 6750) yang mengakhiri sesi. 401 lain, misalnya
  // "Konfirmasi tidak cocok" saat menandatangani, ditampilkan sebagai galat biasa.
  const sessionEnded = response.headers.get('WWW-Authenticate')?.startsWith('Bearer') ?? false
  if (response.status === 401 && sessionEnded && !path.startsWith('/api/auth/')) config.onUnauthorized()
  throw new ApiError(response.status, message)
}

function json(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export const api = {
  async get<T>(path: string): Promise<T> {
    return (await request(path)).json() as Promise<T>
  },
  async postJson<T>(path: string, body: unknown): Promise<T> {
    const response = await request(path, json('POST', body))
    return (response.status === 204 ? undefined : await response.json()) as T
  },
  async putJson<T>(path: string, body: unknown): Promise<T> {
    return (await request(path, json('PUT', body))).json() as Promise<T>
  },
  /** Respons mentah, misalnya PDF beserta header X-Saved-Path. */
  postForm(path: string, form: FormData): Promise<Response> {
    return request(path, { method: 'POST', body: form })
  },
  async putForm<T>(path: string, form: FormData): Promise<T> {
    return (await request(path, { method: 'PUT', body: form })).json() as Promise<T>
  },
  /** Respons mentah untuk GET berkas (misalnya gambar spesimen). */
  raw(path: string): Promise<Response> {
    return request(path)
  },
}
