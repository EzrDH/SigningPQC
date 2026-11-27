<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { SignatureView, VerifyResponse } from '../api/types'
import { crlLine, documentRows, labelWithOid, tsaPolicy } from '../lib/evidence'
import { commonName, formatDateTimeWib } from '../lib/status'

const props = defineProps<{ result: VerifyResponse }>()
const emit = defineEmits<{ close: [] }>()

type Section = 'ringkasan' | 'sertifikat' | 'rantai' | 'timestamp' | 'lapisan'
const sections: { key: Section; label: string }[] = [
  { key: 'ringkasan', label: 'Ringkasan' },
  { key: 'sertifikat', label: 'Sertifikat' },
  { key: 'rantai', label: 'Rantai sertifikat' },
  { key: 'timestamp', label: 'Timestamp' },
  { key: 'lapisan', label: 'Lapisan dokumen' },
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

const revisions = computed(() => props.result.validation?.layers.reduce((m, l) => Math.max(m, l.revision), 0) ?? 0)
const rows = computed(() =>
  props.result.validation ? documentRows(props.result.validation, props.result.signatures.map((s) => s.signatureAlgorithm)) : [],
)
const docTimestamps = computed(
  () => props.result.validation?.layers.filter((l) => l.kind === 'DOCUMENT_TIMESTAMP' && l.signatureTimestamp) ?? [],
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
      <div v-if="section !== 'lapisan'" class="tabs2" role="tablist" aria-label="Tanda tangan">
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
          <dd>{{ labelWithOid(sig.signatureAlgorithm, sig.detail?.signatureAlgorithmOid) }}</dd>
          <dt>Hash</dt>
          <dd>{{ labelWithOid(sig.digestAlgorithm, sig.detail?.digestAlgorithmOid) }}</dd>
          <dt>Level PAdES</dt>
          <dd>{{ sig.padesLevel }}</dd>
          <dt>Waktu tanda tangan</dt>
          <dd>{{ time(sig.signatureTimestamp?.time ?? sig.claimedSigningTime) }}</dd>
          <dt>Keterangan</dt>
          <dd>{{ sig.message }}</dd>
          <template v-if="sig.detail">
            <template v-if="sig.detail.revision">
              <dt>Revisi</dt>
              <dd>Revisi {{ sig.detail.revision }} dari {{ revisions }}</dd>
            </template>
            <dt>Cakupan</dt>
            <dd>{{ sig.detail.coverage }}</dd>
            <template v-if="sig.detail.protectedBy">
              <dt>Dilindungi oleh</dt>
              <dd>{{ sig.detail.protectedBy }}</dd>
            </template>
            <dt>Pencabutan</dt>
            <dd>{{ sig.detail.revocation }}</dd>
          </template>
          <template v-if="result.validation">
            <dt>Waktu pemeriksaan</dt>
            <dd>{{ time(result.validation.validationTime) }}</dd>
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
            <dt>Sidik jari SHA-256</dt>
            <dd class="fp">{{ sig.detail.certificate.fingerprintSha256 }}</dd>
            <dt>Kunci publik</dt>
            <dd>{{ labelWithOid(sig.detail.certificate.keyAlgorithm, sig.detail.certificate.publicKey.oid) }}</dd>
            <dt>Ditandatangani CA dengan</dt>
            <dd>{{ labelWithOid(sig.detail.certificate.signatureAlgorithm.name, sig.detail.certificate.signatureAlgorithm.oid) }}</dd>
            <dt>Penggunaan kunci</dt>
            <dd>{{ sig.detail.certificate.keyUsage }}</dd>
            <dt>Penggunaan kunci lanjutan</dt>
            <dd>
              <template v-if="sig.detail.certificate.extendedKeyUsage.length">
                <span v-for="e in sig.detail.certificate.extendedKeyUsage" :key="e.oid" class="line">{{ labelWithOid(e.name, e.oid) }}</span>
              </template>
              <template v-else>-</template>
            </dd>
            <dt>Kebijakan sertifikat</dt>
            <dd>
              <template v-if="sig.detail.certificate.certificatePolicies.length">
                <span v-for="p in sig.detail.certificate.certificatePolicies" :key="p.oid" class="line">{{ labelWithOid(p.name, p.oid) }}</span>
              </template>
              <template v-else>Tidak dicantumkan</template>
            </dd>
          </dl>
          <p v-else class="hint">Sertifikat tidak bisa dibaca dari tanda tangan ini.</p>
        </template>

        <template v-else-if="section === 'rantai'">
          <ol v-if="sig.detail?.chain.length" class="chain">
            <li v-for="(c, i) in sig.detail.chain" :key="i" :style="{ marginLeft: i * 18 + 'px' }">
              <div class="chain-head">
                <strong>{{ commonName(c.subject) }}</strong>
                <span v-if="c.trustedRoot" class="tag">Root tepercaya</span>
              </div>
              <dl class="kv small">
                <dt>Nomor seri</dt>
                <dd>{{ c.serialHex }}</dd>
                <dt>Berlaku</dt>
                <dd>{{ time(c.notBefore) }} s.d. {{ time(c.notAfter) }}</dd>
                <dt>Sidik jari SHA-256</dt>
                <dd class="fp">{{ c.fingerprintSha256 }}</dd>
                <dt>Pencabutan</dt>
                <dd v-if="c.crls.length">
                  <span v-for="(crl, k) in c.crls" :key="k" class="line">{{ crlLine(crl) }}</span>
                </dd>
                <dd v-else-if="c.trustedRoot">Root dipercaya langsung, tidak diperiksa lewat CRL</dd>
                <dd v-else>Tidak ada CRL dari penerbitnya</dd>
              </dl>
            </li>
          </ol>
          <p v-else class="hint">Rantai sertifikat tidak tersedia.</p>
        </template>

        <template v-else-if="section === 'timestamp'">
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
            <dt>Kebijakan TSA</dt>
            <dd>{{ tsaPolicy(sig.signatureTimestamp.policyOid) }}</dd>
            <dt>Keterangan</dt>
            <dd>{{ sig.signatureTimestamp.message }}</dd>
          </dl>
          <p v-else class="hint">Tanda tangan ini tidak memiliki timestamp.</p>
        </template>

        <template v-else>
          <p class="hint">
            Setiap simpanan PDF menambah satu revisi. Revisi yang lebih baru menutup revisi sebelumnya, jadi tanda tangan
            ML-DSA ikut melindungi tanda tangan RSA di bawahnya.
          </p>
          <div v-if="rows.length" class="scroll-x">
            <table class="layers">
              <thead>
                <tr>
                  <th scope="col">Revisi</th>
                  <th scope="col">Isi</th>
                  <th scope="col">Level</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in rows" :key="r.revision">
                  <td>{{ r.revision }}</td>
                  <td>{{ r.content }}<span v-if="r.detail" class="line hint">{{ r.detail }}</span></td>
                  <td>{{ r.level }}</td>
                  <td>{{ r.status }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="hint">Rincian lapisan belum tersedia karena PKI uji belum dibuat.</p>
          <dl class="kv">
            <dt>Data validasi (DSS)</dt>
            <dd>
              {{ result.dss.present ? `Ada: ${result.dss.certificates} sertifikat, ${result.dss.crls} CRL` : 'Tidak ada' }}
            </dd>
            <template v-for="(l, i) in docTimestamps" :key="'ts' + i">
              <dt>Document timestamp</dt>
              <dd>
                <span class="line">Revisi {{ l.revision }}, {{ time(l.signatureTimestamp!.time) }}</span>
                <span class="line">{{ commonName(l.signatureTimestamp!.tsaSubject) }}, {{ l.signatureTimestamp!.signatureAlgorithm }}</span>
                <span class="line">Nomor seri {{ l.signatureTimestamp!.serialHex }}, imprint {{ l.signatureTimestamp!.imprintAlgorithm }} {{ l.signatureTimestamp!.imprintMatches ? 'cocok' : 'tidak cocok' }}</span>
                <span class="line">{{ tsaPolicy(l.signatureTimestamp!.policyOid) }}</span>
              </dd>
            </template>
            <template v-if="result.validation">
              <dt>Waktu pemeriksaan</dt>
              <dd>{{ time(result.validation.validationTime) }}</dd>
            </template>
            <dt>Jangkar kepercayaan</dt>
            <dd v-if="result.trustAnchors.length">
              <span v-for="a in result.trustAnchors" :key="a.fingerprintSha256" class="line">
                {{ commonName(a.subject) }}<span class="fp line">{{ a.fingerprintSha256 }}</span>
              </span>
            </dd>
            <dd v-else>Tidak ada root tepercaya pada rantai tanda tangan</dd>
          </dl>
        </template>
      </template>
    </div>
  </div>
</template>
