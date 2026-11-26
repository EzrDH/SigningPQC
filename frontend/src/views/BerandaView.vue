<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import { api } from '../api/client'
import type { HistoryEntry } from '../api/types'
import { TONE_COLOR, historyStatus } from '../lib/status'

function greeting(hour: number) {
  if (hour < 11) return 'Selamat pagi'
  if (hour < 15) return 'Selamat siang'
  if (hour < 18) return 'Selamat sore'
  return 'Selamat malam'
}

const hello = greeting(new Date().getHours())
const open = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const entries = ref<HistoryEntry[] | null>(null)

const timeFormat = new Intl.DateTimeFormat('id-ID', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })


const label = computed(() => (open.value ? 'Sembunyikan riwayat' : 'Lihat riwayat dokumen'))

async function toggle() {
  open.value = !open.value
  if (!open.value || entries.value) return
  loading.value = true
  error.value = null
  try {
    entries.value = await api.get<HistoryEntry[]>('/api/me/history')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Riwayat tidak bisa dimuat'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="home">
    <div>
      <h2>{{ hello }}</h2>
      <p class="sub">Pilih yang ingin Anda lakukan.</p>
    </div>
    <div class="acts">
      <RouterLink class="act" to="/unggah/tandatangani" style="text-decoration: none">
        <span class="ic"><AppIcon name="pen" /></span>
        <div>
          <h3>Tandatangani dokumen</h3>
          <p>Bubuhkan tanda tangan elektronik pada PDF.</p>
        </div>
      </RouterLink>
      <RouterLink class="act" to="/unggah/verifikasi" style="text-decoration: none">
        <span class="ic"><AppIcon name="shield" /></span>
        <div>
          <h3>Verifikasi dokumen</h3>
          <p>Periksa keaslian PDF bertanda tangan.</p>
        </div>
      </RouterLink>
    </div>
    <button class="ghost" type="button" :aria-expanded="open" @click="toggle">{{ label }}</button>
    <template v-if="open">
      <p v-if="loading" class="hint">Memuat riwayat…</p>
      <p v-else-if="error" class="err" role="alert">{{ error }}</p>
      <p v-else-if="entries && entries.length === 0" class="hint">
        Belum ada riwayat. Dokumen yang Anda tandatangani atau verifikasi akan muncul di sini.
      </p>
      <div v-else-if="entries" class="hist">
        <div v-for="(entry, i) in entries" :key="i">
          <b>{{ entry.fileName }}</b>
          <span>{{ entry.activity }} · {{ timeFormat.format(new Date(entry.time)) }}</span>
          <span class="chip" :style="{ '--c': TONE_COLOR[historyStatus(entry.status).tone] }">{{
            historyStatus(entry.status).label
          }}</span>
        </div>
      </div>
    </template>
  </div>
</template>
