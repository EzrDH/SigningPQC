import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import OtpInput from './OtpInput.vue'

function boxes(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('input')
}

describe('OtpInput', () => {
  it('menampilkan enam kotak', () => {
    const wrapper = mount(OtpInput, { props: { modelValue: '' } })
    expect(boxes(wrapper)).toHaveLength(6)
  })

  it('mengabaikan huruf: mengetik "12a456" menghasilkan "12456" belum lengkap', async () => {
    const wrapper = mount(OtpInput, {
      props: { modelValue: '', 'onUpdate:modelValue': (v: string) => wrapper.setProps({ modelValue: v }) },
    })
    for (const ch of '12a456') {
      const index = Math.min((wrapper.props('modelValue') as string).length, 5)
      const input = boxes(wrapper)[index]!
      input.element.value = ch
      await input.trigger('input')
    }
    expect(wrapper.props('modelValue')).toBe('12456')
    expect(wrapper.emitted('complete')).toBeUndefined()
  })

  it('tempel "123456" mengisi semua kotak dan emit complete', async () => {
    const wrapper = mount(OtpInput, {
      props: { modelValue: '', 'onUpdate:modelValue': (v: string) => wrapper.setProps({ modelValue: v }) },
    })
    const first = boxes(wrapper)[0]!
    await first.trigger('paste', { clipboardData: { getData: () => '123456' } })
    expect(wrapper.props('modelValue')).toBe('123456')
    expect(boxes(wrapper).map((b) => (b.element as HTMLInputElement).value).join('')).toBe('123456')
    expect(wrapper.emitted('complete')?.[0]).toEqual(['123456'])
  })

  it('tempel teks berspasi "123 456" tetap diambil angkanya saja', async () => {
    const wrapper = mount(OtpInput, {
      props: { modelValue: '', 'onUpdate:modelValue': (v: string) => wrapper.setProps({ modelValue: v }) },
    })
    await boxes(wrapper)[0]!.trigger('paste', { clipboardData: { getData: () => '123 456' } })
    expect(wrapper.props('modelValue')).toBe('123456')
  })
})
