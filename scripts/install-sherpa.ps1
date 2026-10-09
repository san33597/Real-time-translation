# Explicitly install the official sherpa-onnx Android AAR, pinned to its release SHA-256.
# No automatic binary fetch on Gradle configuration or preBuild.
$ErrorActionPreference = "Stop"
$version = "1.13.8"
$expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
$root = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $root ".tooling/maven/com/k2fsa/sherpa-onnx/$version"
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$aar = Join-Path $destination "sherpa-onnx-$version.aar"
if (!(Test-Path $aar)) {
    $temp = Join-Path $destination "download.tmp"
    try {
        Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar" -OutFile $temp
        $digest = (Get-FileHash -Algorithm SHA256 $temp).Hash.ToLowerInvariant()
        if ($digest -ne $expected) { throw "Downloaded AAR SHA-256 mismatch: $digest" }
        Move-Item -Force $temp $aar
    } finally {
        if (Test-Path $temp) { Remove-Item -Force $temp }
    }
}
$digest = (Get-FileHash -Algorithm SHA256 $aar).Hash.ToLowerInvariant()
if ($digest -ne $expected) { throw "Official AAR SHA-256 mismatch: $digest" }
$pom = Join-Path $destination "sherpa-onnx-$version.pom"
@"
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.k2fsa</groupId><artifactId>sherpa-onnx</artifactId>
<version>$version</version><packaging>aar</packaging></project>
"@ | Set-Content -Encoding utf8 $pom
Write-Host "Verified sherpa-onnx $version Android AAR."
