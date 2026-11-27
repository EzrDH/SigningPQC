<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AppLogo from '../components/AppLogo.vue'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const login = ref('')
const password = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function submit() {
  error.value = null
  if (!login.value.trim() || !password.value) {
    error.value = 'Isi username atau email dan password'
    return
  }
  busy.value = true
  try {
    await auth.login(login.value.trim(), password.value)
    password.value = ''
    await router.replace('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Gagal masuk'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <form novalidate @submit.prevent="submit">
      <AppLogo :size="44" />
      <div>
        <h2>Masuk</h2>
        <p class="sub">Gunakan username atau email Anda.</p>
      </div>
      <label class="field">
        <span>Username atau email</span>
        <input v-model="login" type="text" autocomplete="username" autofocus />
      </label>
      <label class="field">
        <span>Password</span>
        <input v-model="password" type="password" autocomplete="current-password" />
      </label>
      <button class="btn p w" type="submit" :disabled="busy">{{ busy ? 'Memeriksa…' : 'Masuk' }}</button>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <p class="foot">Belum punya akun? <RouterLink class="link" to="/daftar">Daftar</RouterLink></p>
    </form>
  </div>
</template>
