# Pemeriksaan silang PKI uji (keluaran PkiDemoMain) dengan OpenSSL 3.5 (mendukung ML-DSA).
# Pemakaian (dari folder repositori):  powershell -ExecutionPolicy Bypass -File scripts\cek-pki.ps1
param([string]$Folder = "backend\pki-output", [string]$Sandi = "uji-signingpqc")

# Pesan stderr OpenSSL diubah jadi teks biasa agar PowerShell 5 tidak menampilkannya sebagai NativeCommandError.
$hasil = @()
function Catat([string]$nama, [bool]$harapanLolos, [int]$kode) {
    $lolos = ($kode -eq 0)
    $status = if ($lolos -eq $harapanLolos) { "SESUAI" } else { "TIDAK SESUAI" }
    $script:hasil += [pscustomobject]@{ Uji = $nama; Harapan = $(if ($harapanLolos) { "lolos" } else { "ditolak" }); Hasil = $(if ($lolos) { "lolos" } else { "ditolak" }); Status = $status }
}

openssl version
Push-Location $Folder
try {
    foreach ($k in "rsa", "mldsa") {
        Write-Host "`n=== Keluarga $k ==="
        openssl x509 -in "$k-root.pem" -noout -subject -ext basicConstraints,keyUsage | Out-Host
        openssl x509 -in "$k-penanda-tangan.pem" -noout -text | Select-String "Signature Algorithm|Public Key Algorithm|Subject:|Not After" | Select-Object -First 5 | Out-Host

        openssl verify -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" -crl_check_all -CRLfile "$k-crl.pem" "$k-penanda-tangan.pem" | Out-Host
        Catat "$k rantai penanda tangan + CRL" $true $LASTEXITCODE
        openssl verify -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" -crl_check_all -CRLfile "$k-crl.pem" -purpose timestampsign "$k-tsa.pem" | Out-Host
        Catat "$k rantai TSA (purpose timestampsign)" $true $LASTEXITCODE
        openssl verify -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" -crl_check_all -CRLfile "$k-crl.pem" "$k-dicabut.pem" 2>&1 | ForEach-Object { "$_" } | Out-Host
        Catat "$k sertifikat dicabut" $false $LASTEXITCODE
        openssl pkcs12 -in "$k-penanda-tangan.p12" -passin "pass:$Sandi" -noout 2>&1 | ForEach-Object { "$_" } | Out-Host
        Catat "$k buka PKCS#12" $true $LASTEXITCODE
        openssl pkcs12 -in "$k-penanda-tangan.p12" -passin "pass:salah" -noout 2>&1 | Out-Null
        Catat "$k PKCS#12 sandi salah" $false $LASTEXITCODE
    }

    Write-Host "`n=== Tanda tangan di 02-hybrid-ca.pdf (rantai diperiksa sampai root) ==="
    openssl cms -verify -binary -inform DER -in tt1.p7s -content tt1-isi.bin -CAfile rsa-root.pem -purpose any -out NUL 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "CMS RSA + rantai" $true $LASTEXITCODE
    openssl cms -verify -binary -inform DER -in tt2.p7s -content tt2-isi.bin -CAfile mldsa-root.pem -purpose any -out NUL 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "CMS ML-DSA + rantai" $true $LASTEXITCODE
    openssl cms -verify -binary -inform DER -in tt2.p7s -content tt2-isi.bin -CAfile rsa-root.pem -purpose any -out NUL 2>&1 | Out-Null
    Catat "CMS ML-DSA dengan root salah" $false $LASTEXITCODE
} finally {
    Pop-Location
}

Write-Host "`n=== Ringkasan ==="
$hasil | Format-Table -AutoSize | Out-Host
$gagal = @($hasil | Where-Object { $_.Status -ne "SESUAI" }).Count
Write-Host ("{0} dari {1} uji sesuai harapan" -f ($hasil.Count - $gagal), $hasil.Count)
