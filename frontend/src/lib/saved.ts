import type { SavedDocument } from '../api/types'

/** PDF hasil tanda tangan/perpanjangan dari jawaban JSON backend (base64) menjadi File di memori. */
export function savedFile(saved: SavedDocument): File {
  const binary = atob(saved.pdfBase64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new File([bytes], saved.fileName, { type: 'application/pdf' })
}
