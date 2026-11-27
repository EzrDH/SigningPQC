import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuth } from './auth'
import { useDocument } from './document'
import { useSettings } from './settings'

describe('sesi berakhir', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('clear() juga mengosongkan dokumen dan pengaturan pengguna sebelumnya', async () => {
    const auth = useAuth()
    const doc = useDocument()
    const settings = useSettings()
    auth.setSession('tok-uji', 'budi.uji')
    await doc.set(new File(['%PDF-1.7'], 'rahasia.pdf', { type: 'application/pdf' }))
    settings.me = { username: 'budi.uji' } as never

    auth.clear()

    expect(auth.token).toBeNull()
    expect(sessionStorage.getItem('tte.token')).toBeNull()
    expect(doc.file).toBeNull()
    expect(doc.bytes).toBeNull()
    expect(settings.me).toBeNull()
  })
})
