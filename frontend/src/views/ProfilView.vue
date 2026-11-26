<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatDate } from '../lib/status'
import { useAuth } from '../stores/auth'
import { useDocument } from '../stores/document'
import { useSettings } from '../stores/settings'

const router = useRouter()
const auth = useAuth()
const settings = useSettings()
const doc = useDocument()
const error = ref<string | null>(null)

const initials = computed(() => {
  const parts = (settings.me?.username ?? auth.username ?? '').split(/[._-]+/).filter(Boolean)
  return (parts.length > 1 ? parts[0]![0]! + parts[1]![0]! : (parts[0] ?? '?').slice(0, 2)).toUpperCase()
})
const short = (iso: string) => new Intl.DateTimeFormat('id-ID', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(iso))
const title = (algorithm: string) => (algorithm.includes('ML-DSA') ? 'Sertifikat ML-DSA' : 'Sertifikat RSA')
const algorithmLabel = (algorithm: string) => (algorithm.includes('ML-DSA') ? `${algorithm}, tahan kuantum` : algorithm)
const serial = (hex: string) => hex.match(/.{1,2}/g)?.join(' ') ?? hex

onMounted(async () => {
  try {
    await settings.load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Profil tidak bisa dimuat'
  }
})

async function logout() {
  await auth.logout().catch(() => undefined)
  settings.reset()
  doc.clear()
  await router.replace('/masuk')
}
</script>

<template>
  <div class="sec">
    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <template v-if="settings.me">
      <div class="profile">
        <span class="av" aria-hidden="true">{{ initials }}</span>
        <div>
          <h2>{{ settings.me.username }}</h2>
          <p class="sub">{{ settings.me.email }}</p>
        </div>
      </div>
      <div class="box">
        <dl class="kv">
          <dt>Username</dt>
          <dd>{{ settings.me.username }}</dd>
          <dt>Email</dt>
          <dd>{{ settings.me.email }}</dd>
          <dt>Terdaftar</dt>
          <dd>{{ formatDate(settings.me.createdAt) }}</dd>
        </dl>
      </div>
      <h3>Sertifikat elektronik</h3>
      <div class="certs">
        <div v-for="c in settings.me.certificates" :key="c.serialHex" class="box">
          <h3>{{ title(c.algorithm) }}</h3>
          <dl class="kv" style="margin-top: 10px">
            <dt>Status</dt>
            <dd :class="{ ok: !c.revoked }" :style="c.revoked ? { color: 'var(--bad)', fontWeight: 700 } : undefined">
              {{ c.revoked ? 'Dicabut' : 'Aktif' }}
            </dd>
            <dt>Algoritma</dt>
            <dd>{{ algorithmLabel(c.algorithm) }}</dd>
            <dt>Penerbit</dt>
            <dd>{{ c.issuer }}</dd>
            <dt>Nomor seri</dt>
            <dd>{{ serial(c.serialHex) }}</dd>
            <dt>Berlaku</dt>
            <dd>{{ short(c.notBefore) }} sampai {{ short(c.notAfter) }}</dd>
          </dl>
        </div>
      </div>
    </template>
    <p v-else-if="!error" class="hint">Memuat profil…</p>
    <div><button class="btn" type="button" @click="logout">Keluar</button></div>
  </div>
</template>
