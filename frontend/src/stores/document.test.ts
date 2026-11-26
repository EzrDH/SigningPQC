import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useDocument } from './document'

describe('store dokumen', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('menyimpan salinan berkas di memori, bukan File yang menunjuk ke disk', async () => {
    const original = new File([new Uint8Array([37, 80, 68, 70])], 'surat.pdf', { type: 'application/pdf' })
    const doc = useDocument()
    await doc.set(original)
    expect(doc.file).not.toBe(original)
    expect(doc.file?.name).toBe('surat.pdf')
    expect(doc.file?.type).toBe('application/pdf')
    expect(new Uint8Array(await doc.file!.arrayBuffer())).toEqual(new Uint8Array([37, 80, 68, 70]))
  })

  it('tipe kosong dianggap application/pdf', async () => {
    const doc = useDocument()
    await doc.set(new File([new Uint8Array([1])], 'tanpa-tipe.pdf'))
    expect(doc.file?.type).toBe('application/pdf')
  })
})
