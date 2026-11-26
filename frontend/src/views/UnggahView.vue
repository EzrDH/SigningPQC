<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DropZone from '../components/DropZone.vue'
import { useDocument } from '../stores/document'

const route = useRoute()
const router = useRouter()
const doc = useDocument()

const mode = computed(() => (route.params.mode === 'verifikasi' ? 'verifikasi' : 'tandatangani'))
const title = computed(() => (mode.value === 'verifikasi' ? 'Verifikasi dokumen' : 'Tandatangani dokumen'))
const sub = computed(() =>
  mode.value === 'verifikasi' ? 'Unggah PDF bertanda tangan yang akan diperiksa.' : 'Unggah PDF yang akan ditandatangani.',
)

async function onFile(file: File) {
  await doc.set(file)
  await router.push(mode.value === 'verifikasi' ? '/verifikasi' : '/tandatangani')
}
</script>

<template>
  <div class="center">
    <RouterLink class="back" to="/">‹ Beranda</RouterLink>
    <div>
      <h2>{{ title }}</h2>
      <p class="sub">{{ sub }}</p>
    </div>
    <DropZone @file="onFile" />
  </div>
</template>
