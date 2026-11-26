<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import PdfViewer from '../components/PdfViewer.vue'
import SignatureDetail from '../components/SignatureDetail.vue'
import VerifyResult from '../components/VerifyResult.vue'
import { api } from '../api/client'
import type { VerifyResponse } from '../api/types'
import { describeResult } from '../lib/status'
import { useDocument } from '../stores/document'

const router = useRouter()
const doc = useDocument()

const result = ref<VerifyResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const showDetail = ref(false)
const renewing = ref(false)
const renewedPath = ref<string | null>(null)

const canRenew = computed(() => (result.value ? describeResult(result.value).canRenew : false))

async function verify() {
  if (!doc.file) return
  loading.value = true
  error.value = null
  try {
    const form = new FormData()
    form.append('file', doc.file)
    result.value = await (await api.postForm('/api/me/verify', form)).json()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Verifikasi gagal'
  } finally {
    loading.value = false
  }
}

async function renew() {
  if (!doc.file) return
  renewing.value = true
  error.value = null
  try {
    const form = new FormData()
    form.append('file', doc.file)
    const response = await api.postForm('/api/me/renew', form)
    const header = response.headers.get('X-Saved-Path')
    const path = header ? decodeURIComponent(header) : null
    const name = path?.split(/[\\/]/).pop() ?? doc.file.name
    // Hasil perpanjangan menjadi dokumen yang ditampilkan, lalu diverifikasi ulang.
    await doc.set(new File([await response.blob()], name, { type: 'application/pdf' }))
    renewedPath.value = path ?? 'folder keluaran'
    await verify()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Perpanjangan gagal'
  } finally {
    renewing.value = false
  }
}

onMounted(async () => {
  if (!doc.bytes) {
    await router.replace('/unggah/verifikasi')
    return
  }
  await verify()
})
</script>

<template>
  <div class="work">
    <div class="viewer">
      <div class="fname"><b>{{ doc.file?.name }}</b></div>
      <PdfViewer v-if="doc.bytes" :data="doc.bytes" @error="error = $event" />
    </div>
    <div class="panel">
      <p v-if="loading" class="hint" role="status">Memeriksa tanda tangan…</p>
      <VerifyResult v-else-if="result" :result="result" />
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <div v-if="renewedPath" class="saved on" role="status">
        <AppIcon name="check" />
        <span>Keabsahan diperpanjang dan tersimpan di<br /><code>{{ renewedPath }}</code></span>
      </div>
      <button v-if="canRenew && !loading" class="btn p w" type="button" :disabled="renewing" @click="renew">
        {{ renewing ? 'Memperpanjang…' : 'Perpanjang keabsahan' }}
      </button>
      <button
        v-if="result && result.signatures.length && !loading"
        class="btn w"
        :class="{ p: !canRenew }"
        type="button"
        @click="showDetail = true"
      >
        Lihat detail
      </button>
      <RouterLink class="btn w" style="text-decoration: none" to="/unggah/verifikasi">Verifikasi dokumen lain</RouterLink>
    </div>
    <SignatureDetail v-if="showDetail && result" :result="result" @close="showDetail = false" />
  </div>
</template>
