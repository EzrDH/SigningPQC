<script setup lang="ts">
/** Ikon garis 24 px (warna mengikuti currentColor). */
defineProps<{ name: 'home' | 'gear' | 'user' | 'pen' | 'shield' | 'check' | 'up' | 'alert' | 'x' }>()
</script>

<template>
  <svg class="i" viewBox="0 0 24 24" aria-hidden="true">
    <path v-if="name === 'home'" d="M4 11l8-7 8 7v9h-5v-6H9v6H4z" />
    <template v-else-if="name === 'gear'">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7" />
    </template>
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" />
    </template>
    <path v-else-if="name === 'pen'" d="M4 20h16M7 16l9.5-9.5 3 3L10 19H7z" />
    <template v-else-if="name === 'shield'">
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
      <path d="M8.5 12l2.5 2.5 4.5-5" />
    </template>
    <path v-else-if="name === 'check'" d="M5 12.5l4.5 4.5L19 7.5" />
    <path v-else-if="name === 'up'" d="M12 16V4M7 9l5-5 5 5M4 16v4h16v-4" />
    <template v-else-if="name === 'alert'">
      <path d="M12 8v5M12 16.5v.5" />
      <circle cx="12" cy="12" r="9" />
    </template>
    <path v-else-if="name === 'x'" d="M6 6l12 12M18 6L6 18" />
  </svg>
</template>
