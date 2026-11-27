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
  policyOid?: string | null
}

export interface CertificateDetail {
  subject: string
  issuer: string
  serialHex: string
  notBefore: string
  notAfter: string
  keyAlgorithm: string
  keyUsage: string
  fingerprintSha256: string
  publicKey: Oid
  signatureAlgorithm: Oid
  extendedKeyUsage: Oid[]
  certificatePolicies: Oid[]
}

/** Nama yang mudah dibaca beserta OID-nya. */
export interface Oid {
  name: string
  oid: string
}

/** CRL penerbit yang tersedia saat pemeriksaan; source menyebut asalnya (DSS dokumen atau CRL lokal PKI uji). */
export interface CrlEvidence {
  issuer: string
  crlNumber: string | null
  thisUpdate: string
  nextUpdate: string | null
  source: string
  listed: boolean
  revokedAt: string | null
}

export interface ChainEntry {
  subject: string
  trustedRoot: boolean
  serialHex: string
  notBefore: string
  notAfter: string
  fingerprintSha256: string
  crls: CrlEvidence[]
}

export interface SignatureDetail {
  certificate: CertificateDetail | null
  chain: ChainEntry[]
  revocation: string
  coverage: string
  revision: number
  signatureAlgorithmOid: string | null
  digestAlgorithmOid: string | null
  protectedBy: string | null
}

export interface SignatureView {
  order: number
  fieldName: string | null
  signatureAlgorithm: string
  digestAlgorithm: string
  padesLevel: string
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
  signatureTimestamp: TimestampReport | null
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
  trustAnchors: { subject: string; fingerprintSha256: string }[]
  dss: { present: boolean; certificates: number; crls: number }
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
