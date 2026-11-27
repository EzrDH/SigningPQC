<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { ActivityEntry, PkiAuthority, PkiSigner, PkiStatus } from '../api/types'
import { activityResult, authorityName, authorityPath, validity } from '../lib/pki'
import { TONE_COLOR, formatDate } from '../lib/status'
import { useSettings } from '../stores/settings'

// Halaman pengembang untuk lingkungan uji (KF-34 dan KF-35): CA, TSA, penanda tangan, dan log aktivitas perangkat.
type Tab = 'ca' | 'signers' | 'log'
const tab = ref<Tab>('ca')

const status = ref<PkiStatus | null>(null)
const statusError = ref<string | null>(null)
const log = ref<ActivityEntry[] | null>(null)
const logError = ref<string | null>(null)
const logBusy = ref(false)

const settings = useSettings()

const short = (iso: string) =>
  new Intl.DateTimeFormat('id-ID', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(iso))
const timeFormat = new Intl.DateTimeFormat('id-ID', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})
const serial = (hex: string) => hex.match(/.{1,2}/g)?.join(' ') ?? hex

async function loadStatus() {
  statusError.value = null
  try {
    status.value = await api.get<PkiStatus>('/api/pki')
  } catch (e) {
    statusError.value = e instanceof Error ? e.message : 'Status PKI uji tidak bisa dimuat'
  }
}

async function loadLog() {
  logBusy.value = true
  logError.value = null
  try {
    log.value = await api.get<ActivityEntry[]>('/api/me/activity')
  } catch (e) {
    logError.value = e instanceof Error ? e.message : 'Log aktivitas tidak bisa dimuat'
  } finally {
    logBusy.value = false
  }
}

onMounted(() => {
  loadStatus()
  loadLog()
})

function show(next: Tab) {
  tab.value = next
  if (next === 'log') loadLog()
}

// Urutan tetap: RSA lalu ML-DSA, root sebelum CA penerbit, agar berpasangan dalam dua kolom.
const ORDER = ['rsa-root', 'rsa-intermediate', 'mldsa-root', 'mldsa-intermediate']
const authorities = computed<PkiAuthority[]>(() =>
  [...(status.value?.cas ?? [])].sort((a, b) => ORDER.indexOf(a.id) - ORDER.indexOf(b.id)),
)
const algorithm = (name: string) => name.replace(/_/g, '-')

// Terbitkan penanda tangan uji (tanpa akun), untuk skrip atau Postman.
const issueName = ref('')
const issueTitle = ref('')
const issueUnit = ref('')
const issueBusy = ref(false)
const issueError = ref<string | null>(null)

async function issue() {
  if (!issueName.value.trim()) {
    issueError.value = 'Nama penanda tangan wajib diisi'
    return
  }
  issueBusy.value = true
  issueError.value = null
  try {
    await api.postJson<PkiSigner>('/api/pki/signers', {
      commonName: issueName.value.trim(),
      title: issueTitle.value.trim() || null,
      unit: issueUnit.value.trim() || null,
    })
    issueName.value = ''
    issueTitle.value = ''
    issueUnit.value = ''
    await loadStatus()
  } catch (e) {
    issueError.value = e instanceof Error ? e.message : 'Penanda tangan tidak bisa diterbitkan'
  } finally {
    issueBusy.value = false
  }
}

// Cabut sertifikat: dikonfirmasi lewat dialog karena tidak bisa dibatalkan.
const revoking = ref<PkiSigner | null>(null)
const revokeBusy = ref(false)
const revokeError = ref<string | null>(null)
const ownSigner = computed(() => revoking.value?.account && revoking.value.commonName === settings.me?.username)

function askRevoke(signer: PkiSigner) {
  revokeError.value = null
  revoking.value = signer
}

async function revoke() {
  if (!revoking.value) return
  revokeBusy.value = true
  revokeError.value = null
  try {
    await api.postJson<PkiSigner>(`/api/pki/signers/${encodeURIComponent(revoking.value.id)}/revoke`, {})
    revoking.value = null
    await loadStatus()
    // Profil ikut menampilkan "Dicabut" bila sertifikat akun sendiri yang dicabut.
    settings.load().catch(() => undefined)
  } catch (e) {
    revokeError.value = e instanceof Error ? e.message : 'Sertifikat tidak bisa dicabut'
  } finally {
    revokeBusy.value = false
  }
}

onMounted(() => settings.load().catch(() => undefined))
</script>

<template>
  <div class="sec wide">
    <div>
      <RouterLink class="back" to="/pengaturan">← Pengaturan</RouterLink>
      <h2>Lingkungan uji</h2>
    </div>

    <div class="tabs2" role="tablist" aria-label="Lingkungan uji">
      <button type="button" role="tab" :aria-selected="tab === 'ca'" :class="{ on: tab === 'ca' }" @click="show('ca')">
        CA dan TSA
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="tab === 'signers'"
        :class="{ on: tab === 'signers' }"
        @click="show('signers')"
      >
        Penanda tangan
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'log'" :class="{ on: tab === 'log' }" @click="show('log')">
        Log aktivitas
      </button>
    </div>

    <p v-if="statusError && tab !== 'log'" class="err" role="alert">{{ statusError }}</p>

    <!-- CA dan TSA -->
    <template v-if="tab === 'ca' && status">
      <p v-if="!status.ready" class="hint">PKI uji belum dibuat. Jalankan backend dengan auto-setup aktif.</p>
      <template v-else>
        <div class="box">
          <dl class="kv">
            <dt>Organisasi</dt>
            <dd>{{ status.organization }}</dd>
            <dt>Dibuat</dt>
            <dd>{{ status.createdAt ? formatDate(status.createdAt) : '-' }}</dd>
            <dt>Folder data</dt>
            <dd class="fp">{{ status.dataDir }}</dd>
          </dl>
        </div>

        <h3>Otoritas sertifikat (CA)</h3>
        <div class="certs two">
          <div v-for="ca in authorities" :key="ca.id" class="box authority">
            <div class="authority-head">
              <h3>{{ authorityName(ca.id) }}</h3>
              <span class="chip" :style="{ '--c': TONE_COLOR[validity(ca.certificate).tone] }">{{
                validity(ca.certificate).label
              }}</span>
            </div>
            <dl class="kv small">
              <dt>Subjek</dt>
              <dd>{{ ca.certificate.subject }}</dd>
              <dt>Penerbit</dt>
              <dd>{{ ca.certificate.issuer }}</dd>
              <dt>Algoritma</dt>
              <dd>{{ algorithm(ca.certificate.algorithm) }}</dd>
              <dt>Nomor seri</dt>
              <dd class="fp">{{ serial(ca.certificate.serialHex) }}</dd>
              <dt>Berlaku</dt>
              <dd>{{ short(ca.certificate.notBefore) }} sampai {{ short(ca.certificate.notAfter) }}</dd>
            </dl>
            <div class="dl">
              <a class="btn" :href="authorityPath(ca.id, 'crt')" :download="`${ca.id}.crt`">Sertifikat .crt</a>
              <a class="btn" :href="authorityPath(ca.id, 'pem')" :download="`${ca.id}.pem`">.pem</a>
              <a class="btn" :href="authorityPath(ca.id, 'crl')" :download="`${ca.id}.crl`">CRL</a>
            </div>
          </div>
        </div>

        <h3>Otoritas waktu (TSA)</h3>
        <div class="certs">
          <div v-for="tsa in status.tsas" :key="tsa.id" class="box authority">
            <div class="authority-head">
              <h3>{{ authorityName(tsa.id) }}</h3>
              <span class="chip" :style="{ '--c': TONE_COLOR[validity(tsa.certificate).tone] }">{{
                validity(tsa.certificate).label
              }}</span>
            </div>
            <dl class="kv small">
              <dt>Subjek</dt>
              <dd>{{ tsa.certificate.subject }}</dd>
              <dt>Penerbit</dt>
              <dd>{{ tsa.certificate.issuer }}</dd>
              <dt>Algoritma</dt>
              <dd>{{ algorithm(tsa.certificate.algorithm) }}</dd>
              <dt>Nomor seri</dt>
              <dd class="fp">{{ serial(tsa.certificate.serialHex) }}</dd>
              <dt>Berlaku</dt>
              <dd>{{ short(tsa.certificate.notBefore) }} sampai {{ short(tsa.certificate.notAfter) }}</dd>
            </dl>
            <div class="dl">
              <a class="btn" :href="authorityPath(tsa.id, 'crt')" :download="`${tsa.id}.crt`">Sertifikat .crt</a>
            </div>
          </div>
        </div>
      </template>
    </template>

    <!-- Penanda tangan -->
    <template v-if="tab === 'signers' && status">
      <form class="box issue" @submit.prevent="issue">
        <h3>Terbitkan penanda tangan uji</h3>
        <div class="grid3">
          <label class="field"><span>Nama</span><input v-model="issueName" type="text" /></label>
          <label class="field"><span>Jabatan</span><input v-model="issueTitle" type="text" /></label>
          <label class="field"><span>Unit</span><input v-model="issueUnit" type="text" /></label>
        </div>
        <p v-if="issueError" class="err" role="alert">{{ issueError }}</p>
        <div><button class="btn p" type="submit" :disabled="issueBusy">
          {{ issueBusy ? 'Menerbitkan…' : 'Terbitkan' }}
        </button></div>
      </form>

      <p v-if="status.signers.length === 0" class="hint">Belum ada penanda tangan.</p>
      <div v-else class="scroll-x">
        <table class="layers">
          <thead>
            <tr>
              <th>Nama</th>
              <th>Jenis</th>
              <th>Nomor seri RSA / ML-DSA</th>
              <th>Berlaku sampai</th>
              <th>Status</th>
              <th><span class="sr">Aksi</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in status.signers" :key="s.id">
              <td>
                <b>{{ s.commonName }}</b>
                <p v-if="s.title || s.unit" class="hint">{{ [s.title, s.unit].filter(Boolean).join(', ') }}</p>
              </td>
              <td>{{ s.account ? 'Akun' : 'Uji' }}</td>
              <td class="fp">{{ s.rsa.serialHex }}<br />{{ s.mlDsa.serialHex }}</td>
              <td>{{ short(s.rsa.notAfter) }}</td>
              <td>
                <span class="chip" :style="{ '--c': TONE_COLOR[validity(s.rsa, new Date(), s.revoked).tone] }">{{
                  validity(s.rsa, new Date(), s.revoked).label
                }}</span>
              </td>
              <td>
                <button v-if="!s.revoked" class="btn small" type="button" @click="askRevoke(s)">Cabut</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Log aktivitas -->
    <template v-if="tab === 'log'">
      <div class="log-head">
        <p class="hint">Aktivitas semua akun di perangkat ini, terbaru di atas.</p>
        <button class="btn small" type="button" :disabled="logBusy" @click="loadLog">Muat ulang</button>
      </div>
      <p v-if="logError" class="err" role="alert">{{ logError }}</p>
      <p v-else-if="log && log.length === 0" class="hint">Belum ada aktivitas.</p>
      <div v-else-if="log" class="scroll-x">
        <table class="layers">
          <thead>
            <tr>
              <th>Waktu</th>
              <th>Siapa</th>
              <th>Aktivitas</th>
              <th>Berkas</th>
              <th>Hasil</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(entry, i) in log" :key="i">
              <td class="nowrap">{{ timeFormat.format(new Date(entry.time)) }}</td>
              <td>{{ entry.actor }}</td>
              <td>{{ entry.action }}</td>
              <td>{{ entry.fileName ?? '-' }}</td>
              <td>
                <span class="chip" :style="{ '--c': TONE_COLOR[activityResult(entry.result).tone] }">{{
                  activityResult(entry.result).label
                }}</span>
                <p v-if="entry.detail" class="hint">{{ entry.detail }}</p>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="logBusy" class="hint">Memuat log…</p>
    </template>

    <div v-if="revoking" class="modal on" @keydown.esc="revoking = null" @click.self="revoking = null">
      <div class="dlg" role="dialog" aria-modal="true" aria-labelledby="rv-title">
        <div>
          <h2 id="rv-title">Cabut sertifikat?</h2>
          <p class="sub">
            Sertifikat RSA dan ML-DSA milik <b>{{ revoking.commonName }}</b> masuk CRL dan tidak bisa dipakai lagi.
            Pencabutan tidak bisa dibatalkan.
          </p>
          <p v-if="ownSigner" class="warnline">Ini sertifikat akun Anda sendiri. Anda tidak bisa menandatangani lagi.</p>
        </div>
        <p v-if="revokeError" class="err" role="alert">{{ revokeError }}</p>
        <div style="display: flex; gap: 10px">
          <button class="btn" type="button" autofocus @click="revoking = null">Batal</button>
          <button class="btn danger" style="flex: 1" type="button" :disabled="revokeBusy" @click="revoke">
            {{ revokeBusy ? 'Mencabut…' : 'Cabut sertifikat' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
