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
