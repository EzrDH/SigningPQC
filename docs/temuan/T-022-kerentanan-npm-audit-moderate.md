# T-022: Lima kerentanan moderate dari `npm audit` di frontend

| Butir | Isi |
|---|---|
| Tanggal | 27 Nov 2026 |
| Jenis | Temuan keamanan dependensi |
| Sprint | 3 |
| Kebutuhan terkait | KNF keamanan, dependensi frontend |
| Lingkungan | Container Linux, Node 22.23.5, npm 10.9.9 |
| Status | Diperbaiki (0 kerentanan) |

## Temuan
`npm audit` di `frontend/` melaporkan 5 kerentanan **moderate**, semuanya dalam satu rantai dependensi **khusus
pengembangan** (tidak ikut terbawa ke `dist/` dan ke aplikasi):

```
@vue/test-utils 2.6.0 (devDependency)
└─ js-beautify 2.0.3
   ├─ editorconfig 3.0.2 ── minimatch 10.2.6
   └─ glob 13.0.7 ───────── minimatch 10.2.6
```

| Paket | Rentang rentan | Advisori |
|---|---|---|
| glob | 9.0.0 sampai 14.0.1 | GHSA-3g3m-2fjr-6pw4: pembatalan lewat AbortSignal bisa dilewati, sehingga penelusuran berkas berlanjut setelah batas waktu |
| minimatch | 7.0.0 sampai 11.0.0 | GHSA-g3hv-75wg-m249: potensi DoS lewat penggandaan pola glob |
| editorconfig, js-beautify, @vue/test-utils | turunan | Bergantung pada versi glob atau minimatch yang rentan |

`npm audit fix --force` menyarankan turun ke `@vue/test-utils@2.4.11`. Itu perubahan besar ke versi lama, jadi tidak
dipakai.

## Perbaikan
Ditambahkan `overrides` di `frontend/package.json` agar rantai tersebut memakai versi yang sudah diperbaiki, tanpa
mengubah `@vue/test-utils`:

```json
"overrides": {
  "glob": "^14.0.6",
  "minimatch": "^11.0.9"
}
```

## Hasil
| Pemeriksaan | Hasil |
|---|---|
| `npm audit` | `found 0 vulnerabilities` |
| `npm ls glob minimatch` | glob 14.0.6 dan minimatch 11.0.9 (overridden) |
| `npm test` (Vitest) | 11 berkas, 67 uji lulus |
| `npm run build` (vue-tsc + Vite) | Berhasil |
| `js-beautify` (dipakai `wrapper.html()` test-utils) | Tetap memformat HTML dengan benar |

## Tindak lanjut
- Hapus `overrides` setelah `js-beautify` atau `@vue/test-utils` merilis versi yang memakai glob dan minimatch yang
  sudah diperbaiki. Periksa dengan `npm audit` setelah memperbarui dependensi.
- Di laptop, jalankan `npm install` sekali setelah menarik perubahan ini agar `node_modules` mengikuti `package-lock.json`.
