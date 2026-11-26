import { describe, expect, it } from 'vitest'
import { historyStatus } from './status'

describe('historyStatus', () => {
  it('memetakan kode status riwayat ke label dan nada', () => {
    expect(historyStatus('SAH')).toEqual({ label: 'Sah', tone: 'ok' })
    expect(historyStatus('TIDAK_SAH')).toEqual({ label: 'Tidak sah', tone: 'bad' })
    expect(historyStatus('TIDAK_DAPAT_DIBUKTIKAN')).toEqual({ label: 'Tidak dapat dibuktikan', tone: 'warn' })
    expect(historyStatus('TANPA_TANDA_TANGAN')).toEqual({ label: 'Tanpa tanda tangan', tone: 'none' })
    expect(historyStatus('TIDAK_DIPERIKSA')).toEqual({ label: 'Tidak diperiksa', tone: 'none' })
  })
  it('kode tak dikenal ditampilkan apa adanya dengan nada netral', () => {
    expect(historyStatus('LAINNYA')).toEqual({ label: 'LAINNYA', tone: 'none' })
  })
})
