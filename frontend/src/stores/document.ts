import { defineStore } from 'pinia'
import { markRaw } from 'vue'

/** PDF yang sedang dikerjakan (hanya di memori, tidak pernah disimpan di browser). */
export const useDocument = defineStore('document', {
  state: () => ({
    file: null as File | null,
    bytes: null as ArrayBuffer | null,
  }),
  actions: {
    /**
     * Isi berkas dibaca sekali ke memori. Unggahan berikutnya (tanda tangan, verifikasi) memakai salinan ini,
     * bukan File yang menunjuk ke disk: bila berkas asli berubah, dipindah, atau dikunci aplikasi lain sesudah
     * dipilih, Firefox memutus unggahan di tengah jalan ("NetworkError", T-015 temuan 7).
     */
    async set(file: File) {
      const bytes = await file.arrayBuffer()
      this.file = markRaw(new File([bytes], file.name, { type: file.type || 'application/pdf' }))
      this.bytes = markRaw(bytes)
    },
    clear() {
      this.file = null
      this.bytes = null
    },
  },
})
