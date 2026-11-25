# Pemeriksaan silang PKI uji dan TSA lokal RFC 3161 (keluaran PkiDemoMain) dengan OpenSSL 3.5 (mendukung ML-DSA).
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

        # Token RFC 3161 dari TSA lokal: tampilkan isi, lalu verifikasi terhadap permintaan dan data.
        openssl ts -reply -in "$k-ts.tsr" -text 2>&1 | ForEach-Object { "$_" } | Select-String "Status:|Policy OID|Hash Algorithm|Time stamp:|Accuracy|Nonce|TSA:" | Out-Host
        if ($k -eq "rsa") {
            openssl ts -verify -in "$k-ts.tsr" -queryfile "$k-ts.tsq" -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" 2>&1 | ForEach-Object { "$_" } | Out-Host
            Catat "$k timestamp vs permintaan (nonce, imprint)" $true $LASTEXITCODE
            openssl ts -verify -in "$k-ts.tsr" -data 02-hybrid-ca.pdf -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" 2>&1 | ForEach-Object { "$_" } | Out-Host
            Catat "$k timestamp vs data PDF" $true $LASTEXITCODE
            openssl ts -verify -in "$k-ts.tsr" -data tt1-isi.bin -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" 2>&1 | Out-Null
            Catat "$k timestamp vs data lain" $false $LASTEXITCODE
        } else {
            # Keterbatasan OpenSSL 3.5: perintah "openssl ts" memakai modul PKCS7 yang belum mendukung ML-DSA
            # (lihat T-006). Token tetap diperiksa lewat modul CMS dan imprint dibandingkan manual.
            openssl ts -verify -in "$k-ts.tsr" -data 02-hybrid-ca.pdf -CAfile "$k-root.pem" -untrusted "$k-intermediate.pem" 2>&1 | Out-Null
            Catat "$k openssl ts -verify (PKCS7 belum dukung ML-DSA)" $false $LASTEXITCODE
            openssl ts -reply -in "$k-ts.tsr" -token_out -out "$k-ts.tst" 2>&1 | Out-Null
            openssl cms -verify -inform DER -in "$k-ts.tst" -CAfile "$k-root.pem" -certfile "$k-intermediate.pem" -purpose any -out "$k-tstinfo.der" 2>&1 | ForEach-Object { "$_" } | Out-Host
            Catat "$k token timestamp lewat openssl cms + rantai" $true $LASTEXITCODE
            $imprint = (openssl asn1parse -inform DER -in "$k-tstinfo.der" | Select-String "OCTET STRING" | Select-Object -First 1).ToString().Split(":")[-1].Trim()
            Write-Host "Imprint token : $imprint"
            $hashPdf = (Get-FileHash -Algorithm SHA512 02-hybrid-ca.pdf).Hash
            Write-Host "SHA-512 PDF   : $hashPdf"
            Catat "$k imprint token = SHA-512 PDF" $true $(if ($imprint -eq $hashPdf) { 0 } else { 1 })
            $hashLain = (Get-FileHash -Algorithm SHA512 tt1-isi.bin).Hash
            Catat "$k imprint token = SHA-512 data lain" $false $(if ($imprint -eq $hashLain) { 0 } else { 1 })
        }
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
