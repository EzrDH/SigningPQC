# Verifikasi silang CMS hasil SpikeMain dengan OpenSSL 3.5 (mendukung ML-DSA).
# Pemakaian (dari folder repositori):  powershell -ExecutionPolicy Bypass -File scripts\cek-openssl.ps1
param([string]$Folder = "backend\spike-output")

openssl version
foreach ($i in 1, 2) {
    $sig = Join-Path $Folder "tt$i.p7s"
    $isi = Join-Path $Folder "tt$i-isi.bin"
    Write-Host "`n=== Tanda tangan $i ==="
    openssl cms -cmsout -print -inform DER -in $sig | Select-String "algorithm:" | Select-Object -First 4
    # -noverify: rantai sertifikat belum diperiksa (sertifikat self-signed pada Sprint 0).
    openssl cms -verify -binary -inform DER -in $sig -content $isi -noverify -out NUL
}
