<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as pdfjs from 'pdfjs-dist'
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import type { Rotation } from '../lib/coords'

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

export interface PageInfo {
  pageNumber: number
  viewport: { width: number; height: number; scale: number; rotation: Rotation }
  pageSizePt: { width: number; height: number }
}

const props = defineProps<{ data: ArrayBuffer; maxWidth?: number }>()
const emit = defineEmits<{ page: [info: PageInfo]; error: [message: string] }>()

const host = ref<HTMLElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
const pdf = shallowRef<PDFDocumentProxy | null>(null)
const pageNumber = ref(1)
const pageCount = ref(0)
const size = ref<{ width: number; height: number } | null>(null)
let task: RenderTask | null = null
let observer: ResizeObserver | null = null
let lastWidth = 0

function normalize(rotation: number): Rotation {
  const r = ((rotation % 360) + 360) % 360
  return (r === 90 || r === 180 || r === 270 ? r : 0) as Rotation
}

async function load() {
  void pdf.value?.loadingTask.destroy()
  pdf.value = null
  try {
    // pdf.js memindahkan buffer ke worker; salinan menjaga data asli tetap utuh untuk diunggah.
    pdf.value = await pdfjs.getDocument({ data: new Uint8Array(props.data.slice(0)) }).promise
    pageCount.value = pdf.value.numPages
    pageNumber.value = 1
    await render()
  } catch {
    emit('error', 'PDF tidak bisa dibuka. Berkas mungkin rusak atau terkunci kata sandi.')
  }
}

async function render() {
  const doc = pdf.value
  const el = canvas.value
  const box = host.value
  if (!doc || !el || !box) return
  const page = await doc.getPage(pageNumber.value)
  const rotation = normalize(page.rotate)
  const base = page.getViewport({ scale: 1, rotation })
  // Pas lebar, tetapi seluruh halaman juga harus terlihat di jendela agar spesimen bisa diletakkan tanpa menggulir.
  const availableWidth = Math.min(box.clientWidth || base.width, props.maxWidth ?? 720)
  const top = box.getBoundingClientRect().top + window.scrollY
  const availableHeight = Math.max(360, window.innerHeight - top - (pageCount.value > 1 ? 72 : 24))
  const scale = Math.min(availableWidth / base.width, availableHeight / base.height)
  const viewport = page.getViewport({ scale, rotation })
  const ratio = window.devicePixelRatio || 1
  el.width = Math.floor(viewport.width * ratio)
  el.height = Math.floor(viewport.height * ratio)
  size.value = { width: viewport.width, height: viewport.height }
  task?.cancel()
  task = page.render({
    canvas: el,
    viewport,
    transform: ratio !== 1 ? [ratio, 0, 0, ratio, 0, 0] : undefined,
  })
  try {
    await task.promise
  } catch {
    return
  }
  const [x1, y1, x2, y2] = page.view as [number, number, number, number]
  emit('page', {
    pageNumber: pageNumber.value,
    viewport: { width: viewport.width, height: viewport.height, scale, rotation },
    pageSizePt: { width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) },
  })
}

function go(delta: number) {
  const next = pageNumber.value + delta
  if (next < 1 || next > pageCount.value) return
  pageNumber.value = next
  void render()
}

onMounted(() => {
  void load()
  if (host.value && 'ResizeObserver' in window) {
    observer = new ResizeObserver(([entry]) => {
      const width = Math.round(entry?.contentRect.width ?? 0)
      if (width && Math.abs(width - lastWidth) > 4) {
        lastWidth = width
        void render()
      }
    })
    observer.observe(host.value)
  }
})
watch(() => props.data, load)
onBeforeUnmount(() => {
  observer?.disconnect()
  task?.cancel()
  void pdf.value?.loadingTask.destroy()
})
defineExpose({ pageNumber, pageCount })
</script>

<template>
  <div ref="host" class="pdf-host">
    <div class="pdf-page" :style="size ? { width: size.width + 'px', height: size.height + 'px' } : undefined">
      <canvas ref="canvas" :style="size ? { width: size.width + 'px', height: size.height + 'px' } : undefined"></canvas>
      <slot />
    </div>
    <div v-if="pageCount > 1" class="pager">
      <button class="btn" type="button" :disabled="pageNumber <= 1" aria-label="Halaman sebelumnya" @click="go(-1)">‹</button>
      <span>Halaman {{ pageNumber }} dari {{ pageCount }}</span>
      <button class="btn" type="button" :disabled="pageNumber >= pageCount" aria-label="Halaman berikutnya" @click="go(1)">›</button>
    </div>
  </div>
</template>
