import type { VerifyResponse } from '../api/types'
/** Status dokumen dari backend (OverallStatus dan status riwayat) dalam bahasa pengguna. */
export type Tone = 'ok' | 'warn' | 'bad' | 'none'

const HISTORY: Record<string, { label: string; tone: Tone }> = {
  SAH: { label: 'Sah', tone: 'ok' },
  TIDAK_SAH: { label: 'Tidak sah', tone: 'bad' },
  TIDAK_DAPAT_DIBUKTIKAN: { label: 'Tidak dapat dibuktikan', tone: 'warn' },
  TANPA_TANDA_TANGAN: { label: 'Tanpa tanda tangan', tone: 'none' },
  TIDAK_DIPERIKSA: { label: 'Tidak diperiksa', tone: 'none' },
}

export function historyStatus(code: string): { label: string; tone: Tone } {
  return HISTORY[code] ?? { label: code, tone: 'none' }
}

export const TONE_COLOR: Record<Tone, string> = {
  ok: 'var(--ok)',
  warn: 'var(--warn)',
  bad: 'var(--bad)',
  none: 'var(--mut)',
}

/** Kode alasan (ReasonCode backend) dalam bahasa biasa untuk pengguna. */
const REASON: Record<string, string> = {
  DOKUMEN_BERUBAH: 'Isi dokumen berubah setelah ditandatangani',
  TANDA_TANGAN_TIDAK_COCOK: 'Tanda tangan tidak cocok dengan sertifikatnya',
  SERTIFIKAT_DICABUT: 'Sertifikat penanda tangan sudah dicabut',
  DICABUT_SETELAH_DITANDATANGANI: 'Sertifikat dicabut setelah ditandatangani',
  SERTIFIKAT_TIDAK_BERLAKU: 'Sertifikat tidak berlaku pada waktu penandatanganan',
  RANTAI_TIDAK_SAH: 'Rantai sertifikat tidak sah',
  ROOT_TIDAK_DIPERCAYA: 'Penerbit sertifikat tidak dikenal oleh aplikasi ini',
  STATUS_PENCABUTAN_TIDAK_DIKETAHUI: 'Status pencabutan sertifikat tidak bisa dipastikan',
  DATA_VALIDASI_TIDAK_ADA: 'Data validasi tidak tersimpan di dokumen',
  TSA_KEDALUWARSA_TANPA_PEMBARUAN: 'Bukti waktu sudah kedaluwarsa dan tidak diperbarui',
  ALGORITMA_TIDAK_AMAN_PADA_WAKTU_PEMERIKSAAN: 'Algoritma yang dipakai sudah tidak aman',
  WAKTU_PEMERIKSAAN_TIDAK_SAH: 'Waktu pemeriksaan tidak sah',
  PERUBAHAN_SETELAH_LAPISAN_TERAKHIR: 'Dokumen diubah setelah ditandatangani',
  PERUBAHAN_ISI_TIDAK_DITANDATANGANI: 'Ada perubahan isi yang tidak ditandatangani',
  TANDA_TANGAN_HYBRID_TIDAK_LENGKAP: 'Tanda tangan RSA dan ML-DSA tidak lengkap',
}
const CHANGE = ['DOKUMEN_BERUBAH', 'PERUBAHAN_SETELAH_LAPISAN_TERAKHIR', 'PERUBAHAN_ISI_TIDAK_DITANDATANGANI']
const NOT_RENEWABLE = 'TSA_KEDALUWARSA_TANPA_PEMBARUAN'

export function reasonText(code: string): string {
  return REASON[code] ?? code
}

export interface ResultPoint {
  text: string
  ok: boolean
}

export interface ResultDescription {
  tone: Tone
  title: string
  subtitle: string
  points: ResultPoint[]
  until: string | null
  canRenew: boolean
  renewMessage: string | null
}

const WIB = 'Asia/Jakarta'
const dateFormat = new Intl.DateTimeFormat('id-ID', { day: 'numeric', month: 'long', year: 'numeric', timeZone: WIB })
const timeFormat = new Intl.DateTimeFormat('id-ID', { hour: '2-digit', minute: '2-digit', timeZone: WIB })

export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}

export function formatDateTimeWib(iso: string): string {
  const d = new Date(iso)
  return `${dateFormat.format(d)}, ${timeFormat.format(d)} WIB`
}

/** Nilai CN dari subjek X.500 ("C=ID,O=...,CN=budi.uji" → "budi.uji"). */
export function commonName(subject: string): string {
  const match = /(?:^|,)\s*CN=([^,]+)/.exec(subject)
  return match?.[1]?.trim() ?? subject
}

/** Ringkasan hasil verifikasi untuk pengguna (spesifikasi bagian 5). */
export function describeResult(res: VerifyResponse): ResultDescription {
  const v = res.validation
  if (res.signatures.length === 0 || !v) {
    return {
      tone: 'none',
      title: 'Dokumen ini tidak memiliki tanda tangan elektronik',
      subtitle: 'Tidak ada tanda tangan yang bisa diperiksa.',
      points: [],
      until: null,
      canRenew: false,
      renewMessage: null,
    }
  }
  const codes = [...new Set([...v.layers.map((l) => l.code), ...v.warnings].filter((c) => c && c !== 'OK'))]
  const hasBoth = (['RSA', 'ML-DSA'] as const).every((f) => res.signatures.some((s) => s.signatureAlgorithm.startsWith(f)))
  // Dokumen yang memuat RSA dan ML-DSA tetapi rusak: penyebab utamanya (misalnya isi berubah) cukup disebut sendiri (T-018).
  const problems = codes.filter(
    (c) =>
      (c !== 'DICABUT_SETELAH_DITANDATANGANI' || v.status !== 'SAH') &&
      (c !== 'TANDA_TANGAN_HYBRID_TIDAK_LENGKAP' || !hasBoth || v.status !== 'TIDAK_SAH'),
  )
  const first = res.signatures[0]!
  const signedAt = first.signatureTimestamp?.time ?? first.claimedSigningTime
  const change = codes.find((c) => CHANGE.includes(c))

  let tone: Tone
  let title: string
  let subtitle: string
  if (v.status === 'SAH') {
    tone = 'ok'
    title = 'Dokumen sah'
    subtitle = codes.length ? codes.map(reasonText).join('. ') : 'Tanda tangan asli, isi tidak berubah'
  } else if (v.status === 'TIDAK_DAPAT_DIBUKTIKAN' && !v.hybridComplete && v.layers.every((l) => l.status === 'SAH')) {
    // KF-23: setiap tanda tangan sah, tetapi tidak berpasangan RSA dan ML-DSA (T-017).
    tone = 'warn'
    title = 'Tidak memenuhi kebijakan hybrid'
    subtitle = 'Tanda tangan sah, tetapi tidak berpasangan RSA dan ML-DSA'
  } else if (v.status === 'TIDAK_DAPAT_DIBUKTIKAN') {
    tone = 'warn'
    title = 'Keabsahan tidak dapat dibuktikan'
    subtitle = problems.length ? problems.map(reasonText).join('. ') : 'Bukti waktu tidak cukup'
  } else {
    tone = 'bad'
    title = 'Dokumen tidak sah'
    subtitle = problems.length ? problems.map(reasonText).join('. ') : 'Tanda tangan tidak sah'
  }

  const points: ResultPoint[] = [
    { text: `Ditandatangani oleh ${commonName(first.signerSubject)}`, ok: true },
  ]
  if (signedAt) points.push({ text: formatDateTimeWib(signedAt), ok: true })
  points.push(change ? { text: reasonText(change), ok: false } : { text: 'Isi tidak berubah sejak ditandatangani', ok: true })
  if (v.hybridComplete) {
    points.push({ text: 'Tanda tangan tahan komputer kuantum (ML-DSA)', ok: true })
  } else if (!res.signatures.some((s) => s.signatureAlgorithm.startsWith('ML-DSA'))) {
    points.push({ text: 'Tanpa tanda tangan tahan komputer kuantum (ML-DSA)', ok: false })
  }

  return {
    tone,
    title,
    subtitle,
    points,
    until: v.status === 'SAH' && v.protectionUntil ? formatDate(v.protectionUntil) : null,
    canRenew: v.renewalRecommended,
    renewMessage: codes.includes(NOT_RENEWABLE) ? 'Masa keabsahan sudah berakhir dan tidak bisa diperpanjang lagi.' : null,
  }
}
