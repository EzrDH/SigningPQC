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
            openssl cms -verify -binary -inform DER -in "$k-ts.tst" -CAfile "$k-root.pem" -certfile "$k-intermediate.pem" -purpose any -out "$k-tstinfo.der" 2>&1 | ForEach-Object { "$_" } | Out-Host
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

    Write-Host "`n=== PAdES B-T di 03-hybrid-bt.pdf (signature timestamp) ==="
    openssl cms -verify -binary -inform DER -in bt1.p7s -content bt1-isi.bin -CAfile rsa-root.pem -purpose any -out NUL 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "B-T CMS RSA + rantai" $true $LASTEXITCODE
    openssl cms -verify -binary -inform DER -in bt2.p7s -content bt2-isi.bin -CAfile mldsa-root.pem -purpose any -out NUL 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "B-T CMS ML-DSA + rantai" $true $LASTEXITCODE
    foreach ($i in 1, 2) {
        $ada = @(openssl cms -cmsout -print -inform DER -in "bt$i.p7s" | Select-String "id-smime-aa-timeStampToken").Count
        Write-Host "bt$i.p7s: atribut signatureTimeStampToken ditemukan $ada kali"
        Catat "B-T tt$i punya atribut signatureTimeStampToken" $true $(if ($ada -ge 1) { 0 } else { 1 })
    }
    openssl ts -verify -token_in -in bt1-ts.tst -data bt1-ts-data.bin -CAfile rsa-root.pem -untrusted rsa-intermediate.pem 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "B-T timestamp RSA atas nilai tanda tangan" $true $LASTEXITCODE
    openssl cms -verify -binary -inform DER -in bt2-ts.tst -CAfile mldsa-root.pem -certfile mldsa-intermediate.pem -purpose any -out bt2-tstinfo.der 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "B-T token timestamp ML-DSA lewat openssl cms + rantai" $true $LASTEXITCODE
    $imprint = (openssl asn1parse -inform DER -in bt2-tstinfo.der | Select-String "OCTET STRING" | Select-Object -First 1).ToString().Split(":")[-1].Trim()
    $hashSig = (Get-FileHash -Algorithm SHA512 bt2-ts-data.bin).Hash
    Write-Host "Imprint token ML-DSA      : $imprint"
    Write-Host "SHA-512 nilai tanda tangan: $hashSig"
    Catat "B-T imprint ML-DSA = SHA-512 nilai tanda tangan" $true $(if ($imprint -eq $hashSig) { 0 } else { 1 })

    Write-Host "`n=== PAdES B-LTA di 07-hybrid-pembaruan-pqc.pdf (document timestamp lapisan 4-6) ==="
    # lta1 = TSA RSA G1, lta2 = TSA RSA G2 (pembaruan), lta3 = TSA ML-DSA (pembaruan pasca-kuantum).
    foreach ($i in 1, 2) {
        openssl ts -reply -token_in -in "lta$i.tst" -text 2>&1 | ForEach-Object { "$_" } | Select-String "Hash Algorithm|Time stamp:|TSA:" | Out-Host
        openssl ts -verify -token_in -in "lta$i.tst" -data "lta$i-isi.bin" -CAfile lt-rsa-root.pem -untrusted lt-rsa-intermediate.pem 2>&1 | ForEach-Object { "$_" } | Out-Host
        Catat "LTA document timestamp RSA $i atas revisi PDF" $true $LASTEXITCODE
    }
    openssl ts -verify -token_in -in lta1.tst -data lta2-isi.bin -CAfile lt-rsa-root.pem -untrusted lt-rsa-intermediate.pem 2>&1 | Out-Null
    Catat "LTA timestamp RSA 1 vs revisi lain" $false $LASTEXITCODE
    openssl cms -verify -binary -inform DER -in lta3.tst -CAfile lt-mldsa-root.pem -certfile lt-mldsa-intermediate.pem -purpose any -out lta3-tstinfo.der 2>&1 | ForEach-Object { "$_" } | Out-Host
    Catat "LTA token ML-DSA lewat openssl cms + rantai" $true $LASTEXITCODE
    $imprint = (openssl asn1parse -inform DER -in lta3-tstinfo.der | Select-String "OCTET STRING" | Select-Object -First 1).ToString().Split(":")[-1].Trim()
    $hashRev = (Get-FileHash -Algorithm SHA512 lta3-isi.bin).Hash
    Write-Host "Imprint token ML-DSA : $imprint"
    Write-Host "SHA-512 revisi PDF   : $hashRev"
    Catat "LTA imprint ML-DSA = SHA-512 revisi PDF" $true $(if ($imprint -eq $hashRev) { 0 } else { 1 })
    $hashLain = (Get-FileHash -Algorithm SHA512 lta2-isi.bin).Hash
    Catat "LTA imprint ML-DSA = SHA-512 revisi lain" $false $(if ($imprint -eq $hashLain) { 0 } else { 1 })
} finally {
    Pop-Location
}

Write-Host "`n=== Ringkasan ==="
$hasil | Format-Table -AutoSize | Out-Host
$gagal = @($hasil | Where-Object { $_.Status -ne "SESUAI" }).Count
Write-Host ("{0} dari {1} uji sesuai harapan" -f ($hasil.Count - $gagal), $hasil.Count)
