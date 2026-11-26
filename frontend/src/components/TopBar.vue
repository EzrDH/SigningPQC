<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from './AppIcon.vue'

const route = useRoute()
const current = computed(() => route.meta.nav as string | undefined)
const items = [
  { key: 'beranda', to: '/', label: 'Beranda', icon: 'home' },
  { key: 'pengaturan', to: '/pengaturan', label: 'Pengaturan', icon: 'gear' },
  { key: 'profil', to: '/profil', label: 'Profil', icon: 'user' },
] as const
</script>

<template>
  <nav class="top-nav" aria-label="Navigasi utama">
    <RouterLink
      v-for="item in items"
      :key="item.key"
      :to="item.to"
      :class="{ cur: current === item.key }"
      :aria-label="item.label"
      :title="item.label"
      :aria-current="current === item.key ? 'page' : undefined"
    >
      <AppIcon :name="item.icon" />
    </RouterLink>
  </nav>
</template>
