import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuth } from '../stores/auth'
import { guard } from './guard'

// Hash history agar tetap jalan saat dibuka dari berkas lokal di Electron (Sprint 4).
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/masuk', component: () => import('../views/MasukView.vue'), meta: { public: true } },
    { path: '/daftar', component: () => import('../views/DaftarView.vue'), meta: { public: true } },
    { path: '/', component: () => import('../views/BerandaView.vue'), meta: { nav: 'beranda' } },
    {
      path: '/unggah/:mode(tandatangani|verifikasi)',
      component: () => import('../views/UnggahView.vue'),
      meta: { nav: 'beranda' },
    },
    { path: '/tandatangani', component: () => import('../views/TandatanganiView.vue'), meta: { nav: 'beranda' } },
    { path: '/verifikasi', component: () => import('../views/VerifikasiView.vue'), meta: { nav: 'beranda' } },
    { path: '/pengaturan', component: () => import('../views/PengaturanView.vue'), meta: { nav: 'pengaturan' } },
    { path: '/profil', component: () => import('../views/ProfilView.vue'), meta: { nav: 'profil' } },
    { path: '/:rest(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => guard({ path: to.path, meta: to.meta as { public?: boolean } }, useAuth().isLoggedIn))
