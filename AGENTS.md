# Panduan untuk asisten AI dan kontributor

- Bahasa dokumen dan pesan aplikasi: Bahasa Indonesia.
- **Setiap temuan atau hasil pengujian wajib dicatat** di `docs/temuan/` sebagai `T-XXX-judul.md` mengikuti templat di `docs/temuan/README.md`, dan ditambahkan ke daftar catatan. Catatan harus sederhana tetapi cukup detail untuk diulang: lingkungan dan versi, commit, langkah, hasil (angka dan tangkapan layar di `docs/temuan/img/`), analisis, tindak lanjut.
- Lingkungan uji saja: semua kunci, sertifikat, dan TSA buatan sendiri. Jangan memakai data asli BSrE dan jangan meng-commit kunci (`*.p12`, `*.key`).
- Backend: Java 25 LTS, Maven (`backend/mvnw.cmd`). Frontend: Vue 3 + Vite (`frontend/`).
- Acuan rencana: `docs/01-PRD-SigningPQC.md`, `docs/02-Kebutuhan-SigningPQC.md`, `docs/03-Jadwal-Sprint-SigningPQC.md`.
