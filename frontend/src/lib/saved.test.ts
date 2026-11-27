import { describe, expect, it } from 'vitest'
import { savedFile } from './saved'

describe('hasil tersimpan', () => {
  it('pdfBase64 menjadi File PDF dengan nama berkas tersimpan', async () => {
    const file = savedFile({ fileName: 'surat-ttd.pdf', savedPath: 'C:\\out\\surat-ttd.pdf', pdfBase64: 'JVBERg==' })
    expect(file.name).toBe('surat-ttd.pdf')
    expect(file.type).toBe('application/pdf')
    expect(new Uint8Array(await file.arrayBuffer())).toEqual(new Uint8Array([37, 80, 68, 70]))
  })
})
