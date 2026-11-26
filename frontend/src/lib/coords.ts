/**
 * Konversi kotak spesimen antara layar (piksel CSS, relatif ke pojok kiri atas halaman yang tampil) dan halaman
 * PDF (point, sistem koordinat halaman tanpa rotasi, y dari atas, seperti VisibleSignature di backend).
 *
 * Rotasi mengikuti /Rotate halaman (searah jarum jam). Untuk titik tampilan (dx, dy) dalam point dan halaman asli
 * berukuran W x H, titik halaman (u, v) dengan v dari atas:
 *   0   : u = dx,     v = dy
 *   90  : u = dy,     v = H - dx
 *   180 : u = W - dx, v = H - dy
 *   270 : u = W - dy, v = dx
 */
export type Rotation = 0 | 90 | 180 | 270

export interface ScreenBox {
  left: number
  top: number
  width: number
  height: number
}

export interface PdfBox {
  x: number
  yFromTop: number
  width: number
  height: number
}

export interface PageView {
  scale: number
  rotation: Rotation
  pageWidthPt: number
  pageHeightPt: number
}

export const DEFAULT_WIDTH_PT = 180
export const DEFAULT_HEIGHT_PT = 75
export const MIN_WIDTH_PT = 120
export const MIN_HEIGHT_PT = 60
const MARGIN_PT = 40

function toPage(dx: number, dy: number, view: PageView): [number, number] {
  const W = view.pageWidthPt
  const H = view.pageHeightPt
  switch (view.rotation) {
    case 90:
      return [dy, H - dx]
    case 180:
      return [W - dx, H - dy]
    case 270:
      return [W - dy, dx]
    default:
      return [dx, dy]
  }
}

function toDisplay(u: number, v: number, view: PageView): [number, number] {
  const W = view.pageWidthPt
  const H = view.pageHeightPt
  switch (view.rotation) {
    case 90:
      return [H - v, u]
    case 180:
      return [W - u, H - v]
    case 270:
      return [v, W - u]
    default:
      return [u, v]
  }
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000
}

/** Kotak di layar ke kotak di halaman PDF tanpa rotasi. */
export function screenToPdf(box: ScreenBox, view: PageView): PdfBox {
  const s = view.scale
  const [u1, v1] = toPage(box.left / s, box.top / s, view)
  const [u2, v2] = toPage((box.left + box.width) / s, (box.top + box.height) / s, view)
  return {
    x: round(Math.min(u1, u2)) + 0,
    yFromTop: round(Math.min(v1, v2)) + 0,
    width: round(Math.abs(u2 - u1)),
    height: round(Math.abs(v2 - v1)),
  }
}

/** Kebalikan screenToPdf. */
export function pdfToScreen(box: PdfBox, view: PageView): ScreenBox {
  const s = view.scale
  const [d1x, d1y] = toDisplay(box.x, box.yFromTop, view)
  const [d2x, d2y] = toDisplay(box.x + box.width, box.yFromTop + box.height, view)
  return {
    left: Math.min(d1x, d2x) * s,
    top: Math.min(d1y, d2y) * s,
    width: Math.abs(d2x - d1x) * s,
    height: Math.abs(d2y - d1y) * s,
  }
}

/** Ukuran halaman sebagaimana tampil (point), lebar dan tinggi tertukar pada rotasi 90 dan 270. */
export function displaySizePt(view: PageView): { width: number; height: number } {
  const turned = view.rotation === 90 || view.rotation === 270
  return turned
    ? { width: view.pageHeightPt, height: view.pageWidthPt }
    : { width: view.pageWidthPt, height: view.pageHeightPt }
}

/** Kotak bawaan 180 x 75 point di kanan bawah halaman sebagaimana tampil, margin 40 point. */
export function defaultBox(view: PageView): PdfBox {
  const size = displaySizePt(view)
  const screen: ScreenBox = {
    left: (size.width - DEFAULT_WIDTH_PT - MARGIN_PT) * view.scale,
    top: (size.height - DEFAULT_HEIGHT_PT - MARGIN_PT) * view.scale,
    width: DEFAULT_WIDTH_PT * view.scale,
    height: DEFAULT_HEIGHT_PT * view.scale,
  }
  return screenToPdf(screen, view)
}
