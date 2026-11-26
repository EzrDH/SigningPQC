<script setup lang="ts">
import { ref } from 'vue'
import type { ScreenBox } from '../lib/coords'

const props = defineProps<{
  modelValue: ScreenBox
  bounds: { width: number; height: number }
  minWidth: number
  minHeight: number
  imageUrl: string | null
  locked?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [box: ScreenBox] }>()

type Mode = 'move' | 'resize'
const drag = ref<{ mode: Mode; x: number; y: number; start: ScreenBox; id: number } | null>(null)

function clamp(box: ScreenBox): ScreenBox {
  const width = Math.min(Math.max(box.width, props.minWidth), props.bounds.width)
  const height = Math.min(Math.max(box.height, props.minHeight), props.bounds.height)
  const left = Math.min(Math.max(box.left, 0), props.bounds.width - width)
  const top = Math.min(Math.max(box.top, 0), props.bounds.height - height)
  return { left, top, width, height }
}

function start(mode: Mode, event: PointerEvent) {
  if (props.locked) return
  event.preventDefault()
  event.stopPropagation()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  drag.value = { mode, x: event.clientX, y: event.clientY, start: { ...props.modelValue }, id: event.pointerId }
}

function move(event: PointerEvent) {
  const d = drag.value
  if (!d || d.id !== event.pointerId) return
  const dx = event.clientX - d.x
  const dy = event.clientY - d.y
  const s = d.start
  if (d.mode === 'move') {
    emit('update:modelValue', clamp({ ...s, left: s.left + dx, top: s.top + dy }))
  } else {
    const width = Math.min(Math.max(s.width + dx, props.minWidth), props.bounds.width - s.left)
    const height = Math.min(Math.max(s.height + dy, props.minHeight), props.bounds.height - s.top)
    emit('update:modelValue', { ...s, width, height })
  }
}

function end(event: PointerEvent) {
  if (drag.value?.id === event.pointerId) drag.value = null
}

function key(event: KeyboardEvent) {
  if (props.locked) return
  const step = event.shiftKey ? 20 : 4
  const b = { ...props.modelValue }
  if (event.key === 'ArrowLeft') b.left -= step
  else if (event.key === 'ArrowRight') b.left += step
  else if (event.key === 'ArrowUp') b.top -= step
  else if (event.key === 'ArrowDown') b.top += step
  else return
  event.preventDefault()
  emit('update:modelValue', clamp(b))
}
</script>

<template>
  <div
    class="spec"
    :class="{ done: locked, dragging: drag }"
    role="group"
    tabindex="0"
    aria-label="Posisi tanda tangan. Seret atau gunakan tombol panah untuk memindahkan."
    :style="{
      left: modelValue.left + 'px',
      top: modelValue.top + 'px',
      width: modelValue.width + 'px',
      height: modelValue.height + 'px',
    }"
    @pointerdown="start('move', $event)"
    @pointermove="move"
    @pointerup="end"
    @pointercancel="end"
    @keydown="key"
  >
    <img v-if="imageUrl" :src="imageUrl" alt="Spesimen tanda tangan" draggable="false" />
    <small v-else>Belum ada spesimen</small>
    <span
      v-if="!locked"
      class="grip"
      aria-hidden="true"
      @pointerdown="start('resize', $event)"
      @pointermove="move"
      @pointerup="end"
      @pointercancel="end"
    ></span>
  </div>
</template>
