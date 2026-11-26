<script setup lang="ts">
import { nextTick, ref } from 'vue'

const LENGTH = 6

const props = defineProps<{ modelValue: string; label?: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  complete: [value: string]
}>()

const boxes = ref<HTMLInputElement[]>([])

function digitsOf(text: string): string {
  return text.replace(/\D/g, '')
}

function focusBox(index: number) {
  nextTick(() => boxes.value[Math.max(0, Math.min(index, LENGTH - 1))]?.focus())
}

function commit(value: string) {
  const next = value.slice(0, LENGTH)
  emit('update:modelValue', next)
  if (next.length === LENGTH) emit('complete', next)
  return next
}

function onInput(index: number, event: Event) {
  const target = event.target as HTMLInputElement
  const typed = digitsOf(target.value)
  const current = props.modelValue
  if (!typed) {
    target.value = current[index] ?? ''
    return
  }
  // Kotak selalu diisi berurutan: digit masuk di posisi pertama yang kosong bila kotak di depannya belum terisi.
  const at = Math.min(index, current.length)
  const next = commit(current.slice(0, at) + typed + current.slice(at + 1))
  target.value = next[index] ?? ''
  focusBox(next.length)
}

function onKeydown(index: number, event: KeyboardEvent) {
  const current = props.modelValue
  if (event.key === 'Backspace') {
    event.preventDefault()
    const at = current[index] ? index : index - 1
    if (at < 0) return
    commit(current.slice(0, at) + current.slice(at + 1))
    focusBox(at)
  } else if (event.key === 'ArrowLeft') {
    focusBox(index - 1)
  } else if (event.key === 'ArrowRight') {
    focusBox(Math.min(index + 1, current.length))
  }
}

function onPaste(event: ClipboardEvent) {
  event.preventDefault()
  const pasted = digitsOf(event.clipboardData?.getData('text') ?? '')
  if (!pasted) return
  const next = commit(pasted)
  focusBox(next.length)
}
</script>

<template>
  <div class="otp" role="group" :aria-label="label ?? 'Kode 6 digit'">
    <input
      v-for="i in LENGTH"
      :key="i"
      ref="boxes"
      type="text"
      inputmode="numeric"
      autocomplete="one-time-code"
      maxlength="6"
      :value="modelValue[i - 1] ?? ''"
      :aria-label="`Digit ${i}`"
      @input="onInput(i - 1, $event)"
      @keydown="onKeydown(i - 1, $event)"
      @paste="onPaste"
      @focus="($event.target as HTMLInputElement).select()"
    />
  </div>
</template>
