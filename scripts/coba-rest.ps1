# Mencoba alur REST lokal dari awal sampai akhir: PKI uji, penanda tangan, tanda tangan hybrid, verifikasi.
# Backend harus sudah berjalan (lihat README). Pemakaian (dari folder repositori):
#   powershell -ExecutionPolicy Bypass -File scripts\coba-rest.ps1 -Pdf "C:\path\dokumen.pdf"
param(
    [Parameter(Mandatory = $true)][string]$Pdf,
    [string]$Api = "http://127.0.0.1:8085",
    [string]$Nama = "Budi Penanda Tangan Uji",
    [string]$Jabatan = "Kepala Seksi Uji",
    [string]$Unit = "Unit Uji",
    [string]$Organisasi = "SigningPQC Uji"
)
$ErrorActionPreference = "Stop"

$status = Invoke-RestMethod "$Api/api/pki"
if (-not $status.ready) {
    Write-Host "PKI uji belum ada, membuat CA RSA dan ML-DSA serta TSA..."
    $status = Invoke-RestMethod -Method Post "$Api/api/pki/setup" -ContentType "application/json" `
        -Body (@{ organization = $Organisasi } | ConvertTo-Json)
}
Write-Host "PKI uji: $($status.organization), folder data $($status.dataDir)"

$signer = Invoke-RestMethod -Method Post "$Api/api/pki/signers" -ContentType "application/json" `
    -Body (@{ commonName = $Nama; title = $Jabatan; unit = $Unit } | ConvertTo-Json)
Write-Host "Penanda tangan $($signer.id): $($signer.rsa.subject)"
Write-Host "  RSA    : $($signer.rsa.algorithm), berlaku sampai $($signer.rsa.notAfter)"
Write-Host "  ML-DSA : $($signer.mlDsa.algorithm), berlaku sampai $($signer.mlDsa.notAfter)"

$keluaran = [System.IO.Path]::ChangeExtension($Pdf, $null).TrimEnd('.') + "-ttd.pdf"
$header = Join-Path $env:TEMP "signingpqc-header.txt"
curl.exe -s -S -f -D $header -o $keluaran -F "file=@$Pdf;type=application/pdf" -F "signerId=$($signer.id)" `
    -F "reason=Persetujuan dokumen" -F "location=Jakarta" "$Api/api/sign"
if ($LASTEXITCODE -ne 0) { throw "Tanda tangan gagal (curl exit $LASTEXITCODE)" }
$waktu = (Select-String -Path $header -Pattern "X-SigningPQC-Waktu-Ms: (\d+)").Matches.Groups[1].Value
Write-Host "PDF bertanda tangan: $keluaran (waktu tanda tangan $waktu ms)"

$hasil = curl.exe -s -S -f -F "file=@$keluaran;type=application/pdf" "$Api/api/verify" | ConvertFrom-Json
Write-Host "`nVerifikasi: $($hasil.summary)"
$hasil.signatures | ForEach-Object {
    [pscustomobject]@{
        No = $_.order; Algoritma = $_.signatureAlgorithm; Level = $_.padesLevel; Sah = $_.valid
        SeluruhDokumen = $_.coversWholeDocument; Timestamp = $_.signatureTimestamp.time
        TSA = $_.signatureTimestamp.tsaSubject
    }
} | Format-Table -AutoSize | Out-Host
