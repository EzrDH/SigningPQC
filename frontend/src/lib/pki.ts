/** Bantuan halaman Lingkungan uji (KF-34, KF-35): nama CA/TSA, alamat berkas, masa berlaku, hasil aktivitas. */
import { historyStatus, type Tone } from './status'

const FAMILY: Record<string, string> = { rsa: 'RSA', mldsa: 'ML-DSA' }

export function authorityName(id: string): string {
  const tsa = /^tsa-(rsa|mldsa)(?:-g(\d+))?$/.exec(id)
  if (tsa) return `TSA ${FAMILY[tsa[1]!]}${tsa[2] ? ` generasi ${tsa[2]}` : ''}`
  const ca = /^(rsa|mldsa)-(root|intermediate)$/.exec(id)
  if (ca) return `CA ${ca[2] === 'root' ? 'root' : 'penerbit'} ${FAMILY[ca[1]!]}`
  return id
}

/** Alamat unduhan: sertifikat CA (.crt/.pem), CRL CA, atau sertifikat TSA (.crt). */
export function authorityPath(id: string, kind: 'crt' | 'pem' | 'crl'): string {
  if (id.startsWith('tsa-')) return `/api/pki/tsa/${id.slice(4)}.crt`
  return kind === 'crl' ? `/api/pki/crl/${id}.crl` : `/api/pki/ca/${id}.${kind}`
}

export function validity(
  cert: { notBefore: string; notAfter: string },
  now: Date = new Date(),
  revoked = false,
): { label: string; tone: Tone } {
  if (revoked) return { label: 'Dicabut', tone: 'bad' }
  if (now < new Date(cert.notBefore)) return { label: 'Belum berlaku', tone: 'warn' }
  if (now > new Date(cert.notAfter)) return { label: 'Kedaluwarsa', tone: 'bad' }
  return { label: 'Berlaku', tone: 'ok' }
}

export function activityResult(code: string): { label: string; tone: Tone } {
  if (code === 'BERHASIL') return { label: 'Berhasil', tone: 'ok' }
  if (code === 'GAGAL') return { label: 'Gagal', tone: 'bad' }
  return historyStatus(code)
}
