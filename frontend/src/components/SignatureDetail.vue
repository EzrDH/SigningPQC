<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { SignatureView, VerifyResponse } from '../api/types'
import { commonName, formatDateTimeWib } from '../lib/status'

const props = defineProps<{ result: VerifyResponse }>()
const emit = defineEmits<{ close: [] }>()

type Section = 'ringkasan' | 'sertifikat' | 'rantai' | 'timestamp'
const sections: { key: Section; label: string }[] = [
  { key: 'ringkasan', label: 'Ringkasan' },
  { key: 'sertifikat', label: 'Sertifikat' },
  { key: 'rantai', label: 'Rantai sertifikat' },
  { key: 'timestamp', label: 'Timestamp' },
]
const sigIndex = ref(0)
const section = ref<Section>('ringkasan')
const closeButton = ref<HTMLButtonElement | null>(null)

const sig = computed<SignatureView | undefined>(() => props.result.signatures[sigIndex.value])
const family = (s: SignatureView) => (s.signatureAlgorithm.includes('ML-DSA') ? 'ML-DSA' : 'RSA')
const time = (iso: string | null | undefined) => (iso ? formatDateTimeWib(iso) : '-')
const intro = computed(() =>
  props.result.signatures.length === 2
    ? 'Dokumen ini memuat dua tanda tangan. Keduanya harus sah.'
    : `Dokumen ini memuat ${props.result.signatures.length} tanda tangan.`,
)

onMounted(() => closeButton.value?.focus())
</script>

<template>
  <div class="modal on" @keydown.esc="emit('close')" @click.self="emit('close')">
    <div class="dlg wide" role="dialog" aria-modal="true" aria-labelledby="sd-title">
      <div style="display: flex; justify-content: space-between; gap: 12px; align-items: flex-start">
        <div>
          <h2 id="sd-title">Detail tanda tangan</h2>
          <p class="sub">{{ intro }}</p>
        </div>
        <button ref="closeButton" class="btn" type="button" @click="emit('close')">Tutup</button>
      </div>
      <div class="tabs2" role="tablist" aria-label="Tanda tangan">
        <button
          v-for="(s, i) in result.signatures"
          :key="i"
          type="button"
          role="tab"
          :aria-selected="i === sigIndex"
          :class="{ on: i === sigIndex }"
          @click="sigIndex = i"
        >
          Tanda tangan {{ i + 1 }} · {{ family(s) }}
        </button>
      </div>
      <div class="tabs2" role="tablist" aria-label="Bagian">
        <button
          v-for="s in sections"
          :key="s.key"
          type="button"
          role="tab"
          :aria-selected="s.key === section"
          :class="{ on: s.key === section }"
          @click="section = s.key"
        >
          {{ s.label }}
        </button>
      </div>

      <template v-if="sig">
        <dl v-if="section === 'ringkasan'" class="kv">
          <dt>Status</dt>
          <dd :class="{ ok: sig.valid }" :style="sig.valid ? undefined : { color: 'var(--bad)' }">
            {{ sig.valid ? 'Sah' : 'Tidak sah' }}
          </dd>
          <dt>Penanda tangan</dt>
          <dd>{{ commonName(sig.signerSubject) }}</dd>
          <dt>Algoritma</dt>
          <dd>{{ sig.signatureAlgorithm }}</dd>
          <dt>Waktu tanda tangan</dt>
          <dd>{{ time(sig.signatureTimestamp?.time ?? sig.claimedSigningTime) }}</dd>
          <dt>Keterangan</dt>
          <dd>{{ sig.message }}</dd>
          <template v-if="sig.detail">
            <dt>Cakupan</dt>
            <dd>{{ sig.detail.coverage }}</dd>
            <dt>Pencabutan</dt>
            <dd>{{ sig.detail.revocation }}</dd>
          </template>
        </dl>

        <template v-else-if="section === 'sertifikat'">
          <dl v-if="sig.detail?.certificate" class="kv">
            <dt>Subjek</dt>
            <dd>{{ sig.detail.certificate.subject }}</dd>
            <dt>Penerbit</dt>
            <dd>{{ sig.detail.certificate.issuer }}</dd>
            <dt>Nomor seri</dt>
            <dd>{{ sig.detail.certificate.serialHex }}</dd>
            <dt>Berlaku sejak</dt>
            <dd>{{ time(sig.detail.certificate.notBefore) }}</dd>
            <dt>Berlaku sampai</dt>
            <dd>{{ time(sig.detail.certificate.notAfter) }}</dd>
            <dt>Kunci</dt>
            <dd>{{ sig.detail.certificate.keyAlgorithm }}</dd>
            <dt>Penggunaan kunci</dt>
            <dd>{{ sig.detail.certificate.keyUsage }}</dd>
          </dl>
          <p v-else class="hint">Sertifikat tidak bisa dibaca dari tanda tangan ini.</p>
        </template>

        <template v-else-if="section === 'rantai'">
          <ol v-if="sig.detail?.chain.length" class="chain">
            <li v-for="(c, i) in sig.detail.chain" :key="i" :style="{ marginLeft: i * 18 + 'px' }">
              {{ commonName(c.subject) }}
              <span v-if="c.trustedRoot" class="tag">Root tepercaya</span>
            </li>
          </ol>
          <p v-else class="hint">Rantai sertifikat tidak tersedia.</p>
        </template>

        <template v-else>
          <dl v-if="sig.signatureTimestamp" class="kv">
            <dt>Waktu</dt>
            <dd>{{ time(sig.signatureTimestamp.time) }}</dd>
            <dt>TSA</dt>
            <dd>{{ commonName(sig.signatureTimestamp.tsaSubject) }}</dd>
            <dt>Nomor seri</dt>
            <dd>{{ sig.signatureTimestamp.serialHex }}</dd>
            <dt>Algoritma</dt>
            <dd>{{ sig.signatureTimestamp.signatureAlgorithm }}</dd>
            <dt>Imprint</dt>
            <dd>{{ sig.signatureTimestamp.imprintAlgorithm }}, {{ sig.signatureTimestamp.imprintMatches ? 'cocok' : 'tidak cocok' }}</dd>
            <dt>Keterangan</dt>
            <dd>{{ sig.signatureTimestamp.message }}</dd>
          </dl>
          <p v-else class="hint">Tanda tangan ini tidak memiliki timestamp.</p>
        </template>
      </template>
    </div>
  </div>
</template>
