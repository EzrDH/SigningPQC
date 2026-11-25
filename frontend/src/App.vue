<script setup lang="ts">
import { onMounted, ref } from 'vue'

interface Health {
  status: string
  java: string
  bouncyCastle: string
  pdfbox: string
}

const health = ref<Health | null>(null)
const error = ref<string | null>(null)

async function checkBackend() {
  error.value = null
  try {
    const response = await fetch('/api/health')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    health.value = (await response.json()) as Health
  } catch (e) {
    health.value = null
    error.value = 'Backend belum berjalan. Jalankan backend lalu coba lagi.'
  }
}

onMounted(checkBackend)
</script>

<template>
  <main class="page">
    <h1>SigningPQC</h1>
    <p class="subtitle">Penandatanganan PDF hybrid RSA + ML-DSA (purwarupa, lingkungan uji)</p>

    <section class="card">
      <h2>Status backend</h2>
      <div v-if="health" class="ok">
        <p>Terhubung</p>
        <ul>
          <li>Java {{ health.java }}</li>
          <li>Bouncy Castle {{ health.bouncyCastle }}</li>
          <li>PDFBox {{ health.pdfbox }}</li>
        </ul>
      </div>
      <p v-else-if="error" class="error">{{ error }}</p>
      <p v-else>Memeriksa...</p>
      <button type="button" @click="checkBackend">Periksa lagi</button>
    </section>

    <p class="note">Halaman tanda tangan dan verifikasi dibuat pada Sprint 2.</p>
  </main>
</template>

<style scoped>
.page {
  max-width: 720px;
  margin: 48px auto;
  padding: 0 24px;
  text-align: left;
}
.subtitle {
  color: #555;
}
.card {
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px 20px;
  margin: 24px 0;
}
.ok p {
  color: #1a7f37;
  font-weight: 600;
}
.error {
  color: #b42318;
}
.note {
  color: #777;
  font-size: 0.9em;
}
</style>
