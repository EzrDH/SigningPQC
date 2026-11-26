<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import QRCode from 'qrcode'
import AppLogo from '../components/AppLogo.vue'
import OtpInput from '../components/OtpInput.vue'
import { api } from '../api/client'
import type { SessionResponse } from '../api/types'
import { cekAkun, cekPassphrase } from '../lib/validasi'
import { useAuth } from '../stores/auth'

interface RegisterStartResponse {
  registrationId: string
  otpauthUri: string
}

const auth = useAuth()
const router = useRouter()

const step = ref<1 | 2 | 3>(1)
const busy = ref(false)
const error = ref<string | null>(null)

const username = ref('')
const email = ref('')
const password = ref('')
const passphrase = ref('')
const passphrase2 = ref('')
const code = ref('')

const registrationId = ref<string | null>(null)
const qrDataUrl = ref<string | null>(null)

function message(e: unknown, fallback: string) {
  return e instanceof Error ? e.message : fallback
}

function toStep2() {
  error.value = cekAkun(username.value, email.value, password.value)
  if (!error.value) step.value = 2
}

async function toStep3() {
  error.value = cekPassphrase(passphrase.value, passphrase2.value)
  if (error.value) return
  busy.value = true
  try {
    const res = await api.postJson<RegisterStartResponse>('/api/auth/register/start', {
      username: username.value.trim().toLowerCase(),
      email: email.value.trim(),
      password: password.value,
      passphrase: passphrase.value,
    })
    registrationId.value = res.registrationId
    qrDataUrl.value = await QRCode.toDataURL(res.otpauthUri, { margin: 1, width: 256 })
    // Rahasia sudah di server; salinan di memori halaman tidak diperlukan lagi.
    password.value = ''
    passphrase.value = ''
    passphrase2.value = ''
    step.value = 3
  } catch (e) {
    error.value = message(e, 'Pendaftaran gagal')
    // Username atau email yang bentrok diperbaiki di langkah 1.
    if (e instanceof Error && /Username|Email/.test(e.message)) step.value = 1
  } finally {
    busy.value = false
  }
}

async function finish() {
  if (busy.value) return
  error.value = null
  if (code.value.length !== 6) {
    error.value = 'Masukkan 6 digit kode dari aplikasi authenticator'
    return
  }
  busy.value = true
  try {
    const session = await api.postJson<SessionResponse>('/api/auth/register/finish', {
      registrationId: registrationId.value,
      code: code.value,
    })
    auth.setSession(session.token, session.username)
    await router.replace('/')
  } catch (e) {
    error.value = message(e, 'Pendaftaran gagal')
    code.value = ''
    if (e instanceof Error && /kedaluwarsa/.test(e.message)) {
      registrationId.value = null
      qrDataUrl.value = null
      step.value = 1
    }
  } finally {
    busy.value = false
  }
}

function back() {
  error.value = null
  step.value = 1
}
</script>

<template>
  <div class="login">
    <div class="reg">
      <AppLogo :size="44" />
      <div class="dots" aria-hidden="true">
        <i v-for="n in 3" :key="n" :class="{ on: n <= step }"></i>
      </div>

      <form v-if="step === 1" class="reg" novalidate @submit.prevent="toStep2">
        <div>
          <h2>Daftar</h2>
          <p class="sub">Langkah 1 dari 3: data akun</p>
        </div>
        <label class="field">
          <span>Username</span>
          <input v-model="username" type="text" autocomplete="username" autocapitalize="none" autofocus />
        </label>
        <label class="field">
          <span>Email</span>
          <input v-model="email" type="text" inputmode="email" autocomplete="email" />
        </label>
        <label class="field">
          <span>Password</span>
          <input v-model="password" type="password" autocomplete="new-password" />
        </label>
        <button class="btn p w" type="submit">Lanjut</button>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <p class="foot">Sudah punya akun? <RouterLink class="link" to="/masuk">Masuk</RouterLink></p>
      </form>

      <form v-else-if="step === 2" class="reg" novalidate @submit.prevent="toStep3">
        <div>
          <h2>Buat passphrase</h2>
          <p class="sub">Langkah 2 dari 3: passphrase membuka kunci tanda tangan Anda</p>
        </div>
        <label class="field">
          <span>Passphrase</span>
          <input v-model="passphrase" type="password" autocomplete="new-password" autofocus />
        </label>
        <label class="field">
          <span>Ulangi passphrase</span>
          <input v-model="passphrase2" type="password" autocomplete="new-password" />
        </label>
        <p class="hint">Minimal 12 karakter. Passphrase tidak bisa dipulihkan.</p>
        <button class="btn p w" type="submit" :disabled="busy">{{ busy ? 'Menyiapkan…' : 'Lanjut' }}</button>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="ghost" type="button" @click="back">Kembali</button>
      </form>

      <form v-else class="reg" novalidate @submit.prevent="finish">
        <div>
          <h2>Hubungkan authenticator</h2>
          <p class="sub">Langkah 3 dari 3: pindai dengan Google Authenticator atau aplikasi sejenis</p>
        </div>
        <div class="qr">
          <img v-if="qrDataUrl" :src="qrDataUrl" alt="Kode QR untuk aplikasi authenticator" width="128" height="128" />
          <ol>
            <li>Buka aplikasi authenticator.</li>
            <li>Pindai kode QR ini.</li>
            <li>Masukkan kode yang muncul.</li>
          </ol>
        </div>
        <div class="field">
          <span>Kode 6 digit</span>
          <OtpInput v-model="code" @complete="finish" />
        </div>
        <button class="btn p w" type="submit" :disabled="busy">{{ busy ? 'Membuat akun…' : 'Buat akun' }}</button>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
      </form>
    </div>
  </div>
</template>
