import { describe, expect, it } from 'vitest'
import { defaultBox, pdfToScreen, screenToPdf } from './coords'

const A4 = { pageWidthPt: 595, pageHeightPt: 842 }

describe('screenToPdf', () => {
  it('rotasi 0 skala 1.5 pada A4 membagi dengan skala', () => {
    expect(screenToPdf({ left: 150, top: 300, width: 270, height: 112.5 }, { scale: 1.5, rotation: 0, ...A4 })).toEqual({
      x: 100,
      yFromTop: 200,
      width: 180,
      height: 75,
    })
  })

  it('rotasi 90: pojok kiri atas tampilan adalah pojok kiri bawah halaman asli, lebar dan tinggi tertukar', () => {
    expect(screenToPdf({ left: 0, top: 0, width: 75, height: 180 }, { scale: 1, rotation: 90, ...A4 })).toEqual({
      x: 0,
      yFromTop: 767,
      width: 180,
      height: 75,
    })
  })

  it('rotasi 180: pojok kiri atas tampilan adalah pojok kanan bawah halaman asli', () => {
    expect(screenToPdf({ left: 0, top: 0, width: 180, height: 75 }, { scale: 1, rotation: 180, ...A4 })).toEqual({
      x: 415,
      yFromTop: 767,
      width: 180,
      height: 75,
    })
  })

  it('rotasi 270: pojok kiri atas tampilan adalah pojok kanan atas halaman asli', () => {
    expect(screenToPdf({ left: 0, top: 0, width: 75, height: 180 }, { scale: 1, rotation: 270, ...A4 })).toEqual({
      x: 415,
      yFromTop: 0,
      width: 180,
      height: 75,
    })
  })
})

describe('pdfToScreen', () => {
  it('kebalikan screenToPdf untuk semua rotasi', () => {
    const pdf = { x: 120, yFromTop: 340, width: 200, height: 80 }
    for (const rotation of [0, 90, 180, 270] as const) {
      const view = { scale: 1.25, rotation, ...A4 }
      const back = screenToPdf(pdfToScreen(pdf, view), view)
      expect(back.x).toBeCloseTo(pdf.x)
      expect(back.yFromTop).toBeCloseTo(pdf.yFromTop)
      expect(back.width).toBeCloseTo(pdf.width)
      expect(back.height).toBeCloseTo(pdf.height)
    }
  })
})

describe('defaultBox', () => {
  it('Letter 612 x 792 rotasi 0: 180 x 75 point di kanan bawah dengan margin 40', () => {
    expect(defaultBox({ scale: 1, rotation: 0, pageWidthPt: 612, pageHeightPt: 792 })).toEqual({
      x: 392,
      yFromTop: 677,
      width: 180,
      height: 75,
    })
  })
})
