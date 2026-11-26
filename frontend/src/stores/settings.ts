import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { Appearance, ConfirmMethod, MeResponse } from '../api/types'

export interface SettingsPatch {
  confirmMethod?: ConfirmMethod
  appearance?: Appearance
  outputDir?: string
  passphrase?: string
}

/** Profil dan pengaturan akun yang sedang masuk, beserta gambar spesimen. */
export const useSettings = defineStore('settings', {
  state: () => ({
    me: null as MeResponse | null,
    specimenUrl: null as string | null,
  }),
  actions: {
    async load() {
      this.me = await api.get<MeResponse>('/api/me')
      await this.refreshSpecimen()
      return this.me
    },
    async save(patch: SettingsPatch) {
      this.me = await api.putJson<MeResponse>('/api/me/settings', patch)
      return this.me
    },
    async uploadSpecimen(file: File) {
      const form = new FormData()
      form.append('file', file)
      this.me = await api.putForm<MeResponse>('/api/me/specimen', form)
      await this.refreshSpecimen()
    },
    async refreshSpecimen() {
      if (this.specimenUrl) URL.revokeObjectURL(this.specimenUrl)
      this.specimenUrl = null
      if (!this.me?.hasSpecimen) return
      const response = await api.raw('/api/me/specimen')
      this.specimenUrl = URL.createObjectURL(await response.blob())
    },
    reset() {
      if (this.specimenUrl) URL.revokeObjectURL(this.specimenUrl)
      this.me = null
      this.specimenUrl = null
    },
  },
})
