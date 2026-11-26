<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import OtpInput from './OtpInput.vue'
import type { ConfirmMethod } from '../api/types'

const props = defineProps<{ method: ConfirmMethod; error?: string | null; busy?: boolean; action?: string }>()
const emit = defineEmits<{ confirm: [value: string]; cancel: [] }>()

const value = ref('')
const dialog = ref<HTMLElement | null>(null)

function submit() {
  if (props.busy) return
  if (props.method === 'TOTP' ? value.value.length !== 6 : !value.value) return
  emit('confirm', value.value)
}

/** Dipanggil induk saat konfirmasi gagal: isian dikosongkan agar pengguna mengetik ulang. */
function clear() {
  value.value = ''
  nextTick(() => dialog.value?.querySelector('input')?.focus())
}
defineExpose({ clear })

onMounted(() => nextTick(() => dialog.value?.querySelector('input')?.focus()))
</script>

<template>
  <div class="modal on" @keydown.esc="emit('cancel')" @click.self="emit('cancel')">
    <form ref="dialog" class="dlg" role="dialog" aria-modal="true" aria-labelledby="cd-title" @submit.prevent="submit">
      <div>
        <h2 id="cd-title">Konfirmasi tanda tangan</h2>
        <p class="sub">
          {{ method === 'PASSPHRASE' ? 'Masukkan passphrase Anda.' : 'Masukkan kode dari aplikasi authenticator.' }}
        </p>
      </div>
      <label v-if="method === 'PASSPHRASE'" class="field">
        <span>Passphrase</span>
        <input v-model="value" type="password" autocomplete="off" />
      </label>
      <div v-else class="field">
        <span>Kode 6 digit</span>
        <OtpInput v-model="value" @complete="submit" />
      </div>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <div style="display: flex; gap: 10px">
        <button class="btn" type="button" @click="emit('cancel')">Batal</button>
        <button class="btn p" style="flex: 1" type="submit" :disabled="busy">
          {{ busy ? 'Menandatangani…' : (action ?? 'Tandatangani') }}
        </button>
      </div>
    </form>
  </div>
</template>
