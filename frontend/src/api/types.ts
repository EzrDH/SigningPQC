/** Bentuk data dari backend (/api/auth, /api/me, /api/verify). */
export type ConfirmMethod = 'PASSPHRASE' | 'TOTP'
export type Appearance = 'VISIBLE' | 'INVISIBLE'

export interface CertificateSummary {
  algorithm: string
  issuer: string
  serialHex: string
  notBefore: string
  notAfter: string
  revoked: boolean
}

export interface MeResponse {
  username: string
  email: string
  createdAt: string
  certificates: CertificateSummary[]
  confirmMethod: ConfirmMethod
  appearance: Appearance
  outputDir: string
  hasSpecimen: boolean
}

export interface HistoryEntry {
  fileName: string
  activity: 'Ditandatangani' | 'Diverifikasi' | 'Diperpanjang'
  time: string
  status: string
  path: string | null
}

export interface SessionResponse {
  token: string
  username: string
}

export interface TimestampReport {
  time: string | null
  tsaSubject: string
  serialHex: string
  signatureAlgorithm: string
  imprintAlgorithm: string
  imprintMatches: boolean
  tokenValid: boolean
  message: string
}

export interface CertificateDetail {
  subject: string
  issuer: string
  serialHex: string
  notBefore: string
  notAfter: string
  keyAlgorithm: string
  keyUsage: string
}

export interface SignatureDetail {
  certificate: CertificateDetail | null
  chain: { subject: string; trustedRoot: boolean }[]
  revocation: string
  coverage: string
}

export interface SignatureView {
  order: number
  fieldName: string | null
  signatureAlgorithm: string
  signerSubject: string
  claimedSigningTime: string | null
  coversWholeDocument: boolean
  valid: boolean
  message: string
  signatureTimestamp: TimestampReport | null
  detail: SignatureDetail | null
}

export type OverallStatus = 'SAH' | 'TIDAK_DAPAT_DIBUKTIKAN' | 'TIDAK_SAH'

export interface LayerReport {
  revision: number
  kind: 'TANDA_TANGAN' | 'DATA_VALIDASI' | 'DOCUMENT_TIMESTAMP'
  pades: string
  algorithm: string
  subject: string
  time: string | null
  code: string
  status: OverallStatus
  detail: string
}

export interface ValidationReport {
  status: OverallStatus
  hybridComplete: boolean
  validationTime: string
  layers: LayerReport[]
  protectionUntil: string | null
  protectionLimitReason: string | null
  renewalRecommended: boolean
  warnings: string[]
}

export interface VerifyResponse {
  fileName: string
  allValid: boolean
  hybridComplete: boolean
  summary: string
  signatures: SignatureView[]
  validation: ValidationReport | null
}

/**
 * Jawaban /api/me/sign dan /api/me/renew. Berupa JSON, bukan berkas PDF, agar pengelola unduhan di browser
 * (misalnya IDM) tidak merebut jawabannya.
 */
export interface SavedDocument {
  fileName: string
  savedPath: string
  pdfBase64: string
}
