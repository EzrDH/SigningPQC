/** Validasi sisi klien, sama dengan aturan backend (AccountService) dan pesannya. */
const USERNAME = /^[a-z0-9._-]{3,32}$/

export function cekAkun(username: string, email: string, password: string): string | null {
  if (!USERNAME.test(username.trim().toLowerCase())) {
    return 'Username 3 sampai 32 karakter: huruf, angka, titik, minus, atau garis bawah'
  }
  if (!/^[^@\s]+@[^@\s]+$/.test(email.trim())) return 'Email tidak sah'
  if (password.length < 8) return 'Password minimal 8 karakter'
  return null
}

export function cekPassphrase(passphrase: string, ulangan: string): string | null {
  if (passphrase.length < 12) return 'Passphrase minimal 12 karakter'
  if (passphrase !== ulangan) return 'Passphrase dan ulangannya tidak sama'
  return null
}
