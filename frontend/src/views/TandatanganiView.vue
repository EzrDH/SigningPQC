<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import PdfViewer, { type PageInfo } from '../components/PdfViewer.vue'
import SpecimenBox from '../components/SpecimenBox.vue'
import { api } from '../api/client'
import {
  MIN_HEIGHT_PT,
  MIN_WIDTH_PT,
  defaultBox,
  pdfToScreen,
  screenToPdf,
  type PageView,
  type ScreenBox,
} from '../lib/coords'
import { useDocument } from '../stores/document'
import { useSettings } from '../stores/settings'

const router = useRouter()
const doc = useDocument()
const settings = useSettings()

const page = ref<PageInfo | null>(null)
const box = ref<ScreenBox | null>(null)
const loadError = ref<string | null>(null)
const confirming = ref(false)
const busy = ref(false)
const confirmError = ref<string | null>(null)
const savedPath = ref<string | null>(null)
const dialog = ref<InstanceType<typeof ConfirmDialog> | null>(null)

const visible = computed(() => settings.me?.appearance === 'VISIBLE')
const view = computed<PageView | null>(() =>
  page.value
    ? {
        scale: page.value.viewport.scale,
        rotation: page.value.viewport.rotation,
        pageWidthPt: page.value.pageSizePt.width,
        pageHeightPt: page.value.pageSizePt.height,
      }
    : null,
)
const hint = computed(() => {
  if (!visible.value) return 'Tampilan tidak tampak dipilih di Pengaturan.'
  return settings.specimenUrl
    ? 'Memakai spesimen dari Pengaturan.'
    : 'Belum ada spesimen. Unggah di Pengaturan, sementara dipakai teks standar.'
})

onMounted(async () => {
  if (!doc.bytes) {
    await router.replace('/unggah/tandatangani')
    return
  }
  try {
    await settings.load()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : 'Pengaturan tidak bisa dimuat'
  }
})

function onPage(info: PageInfo) {
  const previous = page.value
  page.value = info
  const v = view.value!
  // Ganti halaman atau ukuran layar: kotak tetap di posisi PDF yang sama, atau ke posisi bawaan bila belum ada.
  if (box.value && previous && previous.pageNumber === info.pageNumber) {
    const prevView: PageView = {
      scale: previous.viewport.scale,
      rotation: previous.viewport.rotation,
      pageWidthPt: previous.pageSizePt.width,
      pageHeightPt: previous.pageSizePt.height,
    }
    box.value = pdfToScreen(screenToPdf(box.value, prevView), v)
  } else {
    box.value = pdfToScreen(defaultBox(v), v)
  }
}

async function sign(secret: string) {
  if (!doc.file || !page.value) return
  busy.value = true
  confirmError.value = null
  const form = new FormData()
  form.append('file', doc.file)
  form.append('page', String(page.value.pageNumber))
  if (visible.value && box.value && view.value) {
    const pdf = screenToPdf(box.value, view.value)
    form.append('x', String(pdf.x))
    form.append('y', String(pdf.yFromTop))
    form.append('width', String(pdf.width))
    form.append('height', String(pdf.height))
  }
  form.append(settings.me?.confirmMethod === 'PASSPHRASE' ? 'passphrase' : 'code', secret)
  try {
    const response = await api.postForm('/api/me/sign', form)
    const header = response.headers.get('X-Saved-Path')
    savedPath.value = header ? decodeURIComponent(header) : 'folder keluaran'
    confirming.value = false
  } catch (e) {
    confirmError.value = e instanceof Error ? e.message : 'Tanda tangan gagal'
    dialog.value?.clear()
  } finally {
    busy.value = false
  }
}

function openConfirm() {
  confirmError.value = null
  confirming.value = true
}
</script>

<template>
  <div class="work">
    <div class="viewer">
      <div class="fname" :style="page ? { maxWidth: page.viewport.width + 'px' } : undefined">
        <b>{{ doc.file?.name }}</b>
      </div>
      <PdfViewer v-if="doc.bytes" :data="doc.bytes" @page="onPage" @error="loadError = $event">
        <SpecimenBox
          v-if="visible && box && page && view"
          v-model="box"
          :bounds="{ width: page.viewport.width, height: page.viewport.height }"
          :min-width="MIN_WIDTH_PT * view.scale"
          :min-height="MIN_HEIGHT_PT * view.scale"
          :image-url="settings.specimenUrl"
          :locked="!!savedPath"
        />
      </PdfViewer>
    </div>
    <div class="panel">
      <div>
        <h2>Tandatangani</h2>
        <p class="sub">
          {{ visible ? 'Seret spesimen ke tempat tanda tangan.' : 'Dokumen akan ditandatangani tanpa gambar di halaman.' }}
        </p>
      </div>
      <p class="hint">{{ hint }}</p>
      <p v-if="loadError" class="err" role="alert">{{ loadError }}</p>
      <div v-if="savedPath" class="saved on" role="status">
        <AppIcon name="check" />
        <span>Dokumen ditandatangani dan tersimpan di<br /><code>{{ savedPath }}</code></span>
      </div>
      <button v-if="!savedPath" class="btn p w" type="button" :disabled="!page || !settings.me" @click="openConfirm">
        Tandatangani
      </button>
      <RouterLink v-else class="btn w" style="text-align: center; text-decoration: none" to="/unggah/tandatangani">
        Tandatangani dokumen lain
      </RouterLink>
    </div>
    <ConfirmDialog
      v-if="confirming && settings.me"
      ref="dialog"
      :method="settings.me.confirmMethod"
      :error="confirmError"
      :busy="busy"
      @confirm="sign"
      @cancel="confirming = false"
    />
  </div>
</template>
