import { defineStore } from 'pinia'
import { markRaw } from 'vue'

/** PDF yang sedang dikerjakan (hanya di memori, tidak pernah disimpan di browser). */
export const useDocument = defineStore('document', {
  state: () => ({
    file: null as File | null,
    bytes: null as ArrayBuffer | null,
  }),
  actions: {
    async set(file: File) {
      const bytes = await file.arrayBuffer()
      this.file = markRaw(file)
      this.bytes = markRaw(bytes)
    },
    clear() {
      this.file = null
      this.bytes = null
    },
  },
})
