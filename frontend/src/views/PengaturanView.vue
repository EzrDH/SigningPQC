<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import type { Appearance, ConfirmMethod } from '../api/types'
import { useSettings } from '../stores/settings'

const settings = useSettings()
const error = ref<string | null>(null)
const specimenError = ref<string | null>(null)

// Ganti metode konfirmasi: pilihan baru menunggu passphrase dan baru berlaku setelah backend menerima.
const pendingMethod = ref<ConfirmMethod | null>(null)
const methodError = ref<string | null>(null)
const methodBusy = ref(false)
const dialog = ref<InstanceType<typeof ConfirmDialog> | null>(null)

const editingDir = ref(false)
const dirInput = ref('')
const dirError = ref<string | null>(null)
const dirBusy = ref(false)

onMounted(async () => {
  try {
    await settings.load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Pengaturan tidak bisa dimuat'
  }
})

async function setAppearance(appearance: Appearance) {
  error.value = null
  try {
    await settings.save({ appearance })
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Pengaturan tidak tersimpan'
  }
}

async function onSpecimen(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  specimenError.value = null
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    specimenError.value = 'Spesimen harus gambar PNG atau JPEG'
    return
  }
  if (file.size > 1024 * 1024) {
    specimenError.value = 'Spesimen maksimal 1 MB'
    return
  }
  try {
    await settings.uploadSpecimen(file)
  } catch (e) {
    specimenError.value = e instanceof Error ? e.message : 'Spesimen tidak tersimpan'
  }
}

// Kunci render ulang: grup radio digambar ulang dari settings.me agar pilihan lama tampil lagi bila dibatalkan.
const methodKey = ref(0)

function chooseMethod(method: ConfirmMethod) {
  methodKey.value++
  if (method === settings.me?.confirmMethod) return
  methodError.value = null
  pendingMethod.value = method
}

async function confirmMethod(passphrase: string) {
  if (!pendingMethod.value) return
  methodBusy.value = true
  methodError.value = null
  try {
    await settings.save({ confirmMethod: pendingMethod.value, passphrase })
    pendingMethod.value = null
    methodKey.value++
  } catch (e) {
    methodError.value = e instanceof Error ? e.message : 'Metode tidak tersimpan'
    dialog.value?.clear()
  } finally {
    methodBusy.value = false
  }
}

function cancelMethod() {
  pendingMethod.value = null
  methodKey.value++
}

function startDir() {
  dirInput.value = settings.me?.outputDir ?? ''
  dirError.value = null
  editingDir.value = true
}

async function saveDir() {
  dirBusy.value = true
  dirError.value = null
  try {
    await settings.save({ outputDir: dirInput.value.trim() })
    editingDir.value = false
  } catch (e) {
    dirError.value = e instanceof Error ? e.message : 'Folder tidak tersimpan'
  } finally {
    dirBusy.value = false
  }
}
</script>

<template>
  <div class="sec">
    <h2>Pengaturan</h2>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <template v-if="settings.me">
      <div class="box">
        <h3 style="margin-bottom: 12px">Tampilan tanda tangan</h3>
        <div class="choice" role="radiogroup" aria-label="Tampilan tanda tangan">
          <label>
            <input
              type="radio"
              name="appearance"
              :checked="settings.me.appearance === 'VISIBLE'"
              @change="setAppearance('VISIBLE')"
            />Visible
          </label>
          <label>
            <input
              type="radio"
              name="appearance"
              :checked="settings.me.appearance === 'INVISIBLE'"
              @change="setAppearance('INVISIBLE')"
            />Invisible
          </label>
        </div>
        <div v-if="settings.me.appearance === 'VISIBLE'" style="margin-top: 16px">
          <div class="specprev">
            <div class="slot">
              <img v-if="settings.specimenUrl" :src="settings.specimenUrl" alt="Spesimen tanda tangan" />
              <span v-else>Belum ada spesimen</span>
            </div>
            <label class="btn" style="text-align: center">
              {{ settings.me.hasSpecimen ? 'Ganti spesimen' : 'Unggah spesimen' }}
              <input type="file" accept="image/png,image/jpeg" hidden @change="onSpecimen" />
            </label>
          </div>
          <p v-if="specimenError" class="err" role="alert" style="margin-top: 10px">{{ specimenError }}</p>
        </div>
      </div>

      <div class="box">
        <h3 style="margin-bottom: 12px">Konfirmasi tanda tangan</h3>
        <div :key="methodKey" class="choice" role="radiogroup" aria-label="Konfirmasi tanda tangan">
          <label>
            <input
              type="radio"
              name="method"
              :checked="settings.me.confirmMethod === 'PASSPHRASE'"
              @change="chooseMethod('PASSPHRASE')"
            />Passphrase
          </label>
          <label>
            <input
              type="radio"
              name="method"
              :checked="settings.me.confirmMethod === 'TOTP'"
              @change="chooseMethod('TOTP')"
            />TOTP
          </label>
          <label class="dis"><input type="radio" name="method" disabled />Passkey <span class="soon">Segera</span></label>
        </div>
      </div>

      <div class="box">
        <h3 style="margin-bottom: 12px">Lokasi penyimpanan</h3>
        <div v-if="!editingDir" class="path">
          <code>{{ settings.me.outputDir }}</code>
          <button class="btn" type="button" @click="startDir">Ubah</button>
        </div>
        <form v-else class="path" @submit.prevent="saveDir">
          <input v-model="dirInput" type="text" aria-label="Folder keluaran" style="flex: 1 1 260px" autofocus />
          <button class="btn" type="button" @click="editingDir = false">Batal</button>
          <button class="btn p" type="submit" :disabled="dirBusy">Simpan</button>
        </form>
        <p v-if="dirError" class="err" role="alert" style="margin-top: 10px">{{ dirError }}</p>
      </div>

      <div class="box">
        <h3 style="margin-bottom: 12px">Lingkungan uji</h3>
        <div class="path">
          <code>CA, TSA, penanda tangan, dan log aktivitas</code>
          <RouterLink class="btn" to="/lingkungan-uji">Buka</RouterLink>
        </div>
      </div>
    </template>
    <p v-else-if="!error" class="hint">Memuat pengaturan…</p>

    <ConfirmDialog
      v-if="pendingMethod"
      ref="dialog"
      method="PASSPHRASE"
      title="Ganti metode konfirmasi"
      action="Simpan"
      busy-label="Menyimpan…"
      :error="methodError"
      :busy="methodBusy"
      @confirm="confirmMethod"
      @cancel="cancelMethod"
    />
  </div>
</template>
