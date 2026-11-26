export interface GuardTarget {
  path: string
  meta: { public?: boolean }
}

/** Rute non-publik butuh sesi; halaman masuk/daftar tidak perlu dibuka lagi bila sudah masuk. */
export function guard(to: GuardTarget, loggedIn: boolean): string | true {
  if (!to.meta.public && !loggedIn) return '/masuk'
  if (to.meta.public && loggedIn) return '/'
  return true
}
