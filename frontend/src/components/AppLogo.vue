<script setup lang="ts">
/** Logo sementara: perisai generik (bukan logo BSSN/BSrE). */
defineProps<{ size?: number }>()
</script>

<template>
  <svg class="logo" :width="size ?? 34" :height="size ?? 34" viewBox="0 0 40 40" role="img" aria-label="Logo aplikasi">
    <path d="M20 3l14 5v10c0 9.5-6 16-14 19-8-3-14-9.5-14-19V8z" fill="#0f3d91" />
    <path d="M20 7.5l10 3.6v7.2c0 7-4.3 11.8-10 14.2-5.7-2.4-10-7.2-10-14.2v-7.2z" fill="#2f63c4" />
    <path d="M14 19.5l4.5 4.5 8-9" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
</template>
