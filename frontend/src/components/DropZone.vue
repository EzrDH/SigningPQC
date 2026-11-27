<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from './AppIcon.vue'

const emit = defineEmits<{ file: [file: File] }>()
const over = ref(false)
const error = ref<string | null>(null)

function isPdf(file: File) {
  return file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
}

function take(files: FileList | null | undefined) {
  const file = files?.[0]
  if (!file) return
  if (!isPdf(file)) {
    error.value = `${file.name} bukan PDF. Pilih berkas berakhiran .pdf.`
    return
  }
  error.value = null
  emit('file', file)
}

function onDrop(event: DragEvent) {
  over.value = false
  take(event.dataTransfer?.files)
}

function onChange(event: Event) {
  const input = event.target as HTMLInputElement
  take(input.files)
  input.value = ''
}
</script>

<template>
  <label
    class="drop"
    :class="{ over }"
    @dragover.prevent="over = true"
    @dragleave="over = false"
    @drop.prevent="onDrop"
  >
    <input type="file" accept="application/pdf,.pdf" @change="onChange" />
    <AppIcon name="up" />
    <b>Tarik dan lepas PDF di sini</b>
    <p>atau klik untuk memilih berkas</p>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
  </label>
</template>
