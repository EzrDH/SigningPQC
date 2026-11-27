<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import type { VerifyResponse } from '../api/types'
import { TONE_COLOR, describeResult } from '../lib/status'

const props = defineProps<{ result: VerifyResponse }>()
const d = computed(() => describeResult(props.result))
const color = computed(() => TONE_COLOR[d.value.tone])
const icon = computed(() => (d.value.tone === 'ok' ? 'check' : d.value.tone === 'bad' ? 'x' : 'alert'))
</script>

<template>
  <div class="verdict" role="status">
    <div class="seal" :style="{ background: color }"><AppIcon :name="icon" /></div>
    <div>
      <h2 :style="{ color }">{{ d.title }}</h2>
      <p>{{ d.subtitle }}</p>
    </div>
  </div>
  <p v-if="d.warning" class="warnline" role="note">{{ d.warning }}</p>
  <ul v-if="d.points.length" class="facts">
    <li v-for="p in d.points" :key="p.text">
      <AppIcon :name="p.ok ? 'check' : 'x'" :style="p.ok ? undefined : { color: 'var(--bad)' }" />
      <span>{{ p.text }}</span>
    </li>
  </ul>
  <p v-if="d.until" class="hint">Keabsahan terjaga sampai {{ d.until }}.</p>
  <p v-if="d.canRenew" class="hint">Masa keabsahan hampir berakhir. Perpanjang agar dokumen tetap bisa dibuktikan.</p>
  <p v-if="d.renewMessage" class="hint">{{ d.renewMessage }}</p>
</template>
