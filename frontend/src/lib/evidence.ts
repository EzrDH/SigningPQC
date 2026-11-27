import type { CrlEvidence, ValidationReport } from '../api/types'
import { commonName, formatDateTimeWib, historyStatus } from './status'

/** Bukti verifikasi (T-019): nama yang mudah dibaca beserta OID dan rincian lapisan dokumen. */

const TSA_POLICY: Record<string, string> = {
  '1.3.6.1.4.1.32473.1.1': 'Kebijakan TSA uji',
}

export function labelWithOid(name: string, oid: string | null | undefined): string {
  if (!oid) return name
  return name === oid ? `OID ${oid}` : `${name} (OID ${oid})`
}

export function tsaPolicy(oid: string | null | undefined): string {
  if (!oid) return '-'
  return labelWithOid(TSA_POLICY[oid] ?? oid, oid)
}

export function crlLine(crl: CrlEvidence): string {
  const name = crl.crlNumber ? `CRL nomor ${crl.crlNumber}` : 'CRL'
  const until = crl.nextUpdate ? `, berlaku s.d. ${formatDateTimeWib(crl.nextUpdate)}` : ''
  const listed = crl.listed
    ? `Sertifikat dicabut pada ${crl.revokedAt ? formatDateTimeWib(crl.revokedAt) : 'waktu tidak diketahui'}.`
    : 'Sertifikat tidak tercantum.'
  return `${name} dari ${commonName(crl.issuer)}, terbit ${formatDateTimeWib(crl.thisUpdate)}${until}. Sumber: ${crl.source}. ${listed}`
}

export interface DocumentRow {
  revision: number
  content: string
  level: string
  status: string
  detail: string
}

const KIND: Record<string, string> = {
  DATA_VALIDASI: 'Data validasi (DSS)',
  DOCUMENT_TIMESTAMP: 'Document timestamp',
}

/**
 * Baris tabel lapisan dokumen: revisi 1 (dokumen asli) lalu tiap lapisan.
 *
 * @param signatureAlgorithms algoritma tanda tangan sesuai urutan, untuk menamai lapisan tanda tangan
 */
export function documentRows(validation: ValidationReport, signatureAlgorithms: string[]): DocumentRow[] {
  const rows: DocumentRow[] = [{ revision: 1, content: 'Dokumen asli', level: '-', status: '-', detail: 'Isi sebelum ditandatangani' }]
  let signature = 0
  for (const l of validation.layers) {
    const content =
      l.kind === 'TANDA_TANGAN' ? `Tanda tangan ${signatureAlgorithms[signature++] ?? l.algorithm}` : (KIND[l.kind] ?? l.kind)
    const status = historyStatus(l.status).label
    rows.push({ revision: l.revision, content, level: l.pades, status, detail: l.detail === status ? '' : l.detail })
  }
  return rows
}
