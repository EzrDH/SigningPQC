import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { SessionResponse } from '../api/types'
import { useDocument } from './document'
import { useSettings } from './settings'

const TOKEN_KEY = 'tte.token'
const USER_KEY = 'tte.username'

/** Sesi disimpan di sessionStorage sehingga hilang saat jendela ditutup. */
export const useAuth = defineStore('auth', {
  state: () => ({
    token: sessionStorage.getItem(TOKEN_KEY) as string | null,
    username: sessionStorage.getItem(USER_KEY) as string | null,
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    setSession(token: string, username: string) {
      this.token = token
      this.username = username
      sessionStorage.setItem(TOKEN_KEY, token)
      sessionStorage.setItem(USER_KEY, username)
    },
    /** Mengakhiri sesi di browser, termasuk dokumen dan pengaturan pengguna ini agar tidak terlihat pengguna berikutnya. */
    clear() {
      useDocument().clear()
      useSettings().reset()
      this.token = null
      this.username = null
      sessionStorage.removeItem(TOKEN_KEY)
      sessionStorage.removeItem(USER_KEY)
    },
    async login(login: string, password: string) {
      const session = await api.postJson<SessionResponse>('/api/auth/login', { login, password })
      this.setSession(session.token, session.username)
    },
    async logout() {
      try {
        await api.postJson('/api/auth/logout', {})
      } finally {
        this.clear()
      }
    },
  },
})
