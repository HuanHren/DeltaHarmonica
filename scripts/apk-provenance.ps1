Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-test-safety.ps1')
$script:SupportedApkAgpVersion = '9.5.1'

function Get-ApkAudit {
    param([Parameter(Mandatory)][string]$Apk,[switch]$Instrumentation)
    $aapt=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe'
    $signer=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\apksigner.bat'
    if (!(Test-Path -LiteralPath $signer -PathType Leaf)) { throw 'apksigner is unavailable' }
    $path=(Resolve-Path -LiteralPath $Apk -ErrorAction Stop).ProviderPath
    $before=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $identity=Get-ApkIdentity -Aapt $aapt -Apk $path -Instrumentation:$Instrumentation
    $badging=@(& $aapt dump badging $path 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot parse APK version' }
    $packageLines=@($badging | Where-Object { $_ -cmatch '^package: ' })
    if ($packageLines.Count -ne 1 -or $packageLines[0] -cnotmatch "versionCode='([^']*)' versionName='([^']*)'") { throw 'APK version is missing or ambiguous' }
    $versionCode=$Matches[1]; $versionName=$Matches[2]
    $verification=@(& $signer verify --verbose --print-certs $path 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    $signers=@($verification | Where-Object { $_ -cmatch '^Number of signers: 1\s*$' })
    $certs=@($verification | Where-Object { $_ -cmatch '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$' })
    if ($signers.Count -ne 1 -or $certs.Count -ne 1 -or $certs[0] -cnotmatch '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$') { throw 'APK signer is missing or ambiguous' }
    $certificate=$Matches[1].ToLowerInvariant()
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entries=@($zip.Entries | Where-Object { $_.FullName -ceq 'AndroidManifest.xml' })
        if ($entries.Count -ne 1) { throw 'APK manifest missing or ambiguous' }
        $stream=$entries[0].Open(); $sha=[Security.Cryptography.SHA256]::Create()
        try { $manifest=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
        finally { $sha.Dispose(); $stream.Dispose() }
    } finally { $zip.Dispose() }
    $after=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($before -cne $after) { throw 'APK changed during inspection' }
    return [pscustomobject]@{ applicationId=$identity.Package; targetPackage=$identity.Target; runner=$identity.Runner; apkPath=$path; apkSha256=$after; certificateSha256=$certificate; manifestSha256=$manifest; versionCode=$versionCode; versionName=$versionName; signatureVerified=$true }
}

function New-ApkProvenance {
    param([Parameter(Mandatory)][string]$HostApk,[Parameter(Mandatory)][string]$TestApk)
    $aapt=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe'
    $hostMetadata=Join-Path $script:SafetyProjectRoot 'app/build/outputs/apk/instrumented/output-metadata.json'
    $testMetadata=Join-Path $script:SafetyProjectRoot 'app/build/outputs/apk/androidTest/instrumented/output-metadata.json'
    $rootBuild=Join-Path $script:SafetyProjectRoot 'build.gradle.kts'
    $appBuild=Join-Path $script:SafetyProjectRoot 'app/build.gradle.kts'
    $agpDeclarations=[regex]::Matches((Get-Content -LiteralPath $rootBuild -Raw), 'id\("com\.android\.application"\)\s+version\s+"([^"]+)"')
    if ($agpDeclarations.Count -ne 1 -or $agpDeclarations[0].Groups[1].Value -cne $script:SupportedApkAgpVersion) { throw 'Unsupported or ambiguous AGP declaration' }
    $validated=Assert-ApkPair $aapt $HostApk $TestApk $hostMetadata $testMetadata
    $hostAudit=Get-ApkAudit $HostApk
    $testAudit=Get-ApkAudit $TestApk -Instrumentation
    if ($hostAudit.certificateSha256 -cne $testAudit.certificateSha256) { throw 'Host and test signing certificates differ' }
    if ($hostAudit.apkSha256 -cne $validated.AppHash.ToLowerInvariant() -or $testAudit.apkSha256 -cne $validated.TestHash.ToLowerInvariant()) { throw 'APK changed while recording provenance' }
    return [pscustomobject]@{ schemaVersion=1; buildVariant='instrumented'; agpVersion=$script:SupportedApkAgpVersion; pairScope='observed-together-not-build-session-proof'; host=$hostAudit; test=$testAudit; hostMetadataSha256=(Get-FileHash -LiteralPath $hostMetadata -Algorithm SHA256).Hash.ToLowerInvariant(); testMetadataSha256=(Get-FileHash -LiteralPath $testMetadata -Algorithm SHA256).Hash.ToLowerInvariant(); rootBuildSha256=(Get-FileHash -LiteralPath $rootBuild -Algorithm SHA256).Hash.ToLowerInvariant(); appBuildSha256=(Get-FileHash -LiteralPath $appBuild -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Assert-ApkSnapshotMatches {
    param($Record,[string]$HostApk,[string]$TestApk)
    if ($null -eq $Record -or $Record.schemaVersion -ne 1 -or $Record.buildVariant -cne 'instrumented' -or $Record.agpVersion -cne $script:SupportedApkAgpVersion -or $Record.pairScope -cne 'observed-together-not-build-session-proof') { throw 'Missing, unsupported, or invalid provenance' }
    $actual=New-ApkProvenance -HostApk $HostApk -TestApk $TestApk
    foreach ($key in @('hostMetadataSha256','testMetadataSha256','rootBuildSha256','appBuildSha256')) { if ($Record.$key -cne $actual.$key) { throw "Provenance differs: $key" } }
    foreach ($part in @('host','test')) {
        foreach ($key in @('applicationId','targetPackage','runner','apkPath','apkSha256','certificateSha256','manifestSha256','versionCode','versionName','signatureVerified')) {
            if ($Record.$part.$key -cne $actual.$part.$key) { throw "Provenance differs: $part.$key" }
        }
    }
}

# A matching, unsigned snapshot cannot prove both APKs came from the same
# build invocation. Until that evidence exists, never authorize the pair.
function Assert-ApkProvenanceRecord {
    param($Record,[string]$HostApk,[string]$TestApk)
    Assert-ApkSnapshotMatches $Record $HostApk $TestApk
    throw 'Build-session linkage is unverified; provenance cannot authorize device execution'
}

# This accepts only a synthetic inventory, not an AGP task or UTP execution.
# It cannot establish completeness of real UTP inputs and must never authorize a device task.
function Assert-SyntheticUtpInventory {
    param($Record,[object[]]$Inputs,[AllowNull()][AllowEmptyCollection()][object[]]$AdditionalApks,[string]$Source)
    if ($Source -cne 'offline-explicit-fixture') { throw 'Unknown inventory source' }
    if ($null -eq $AdditionalApks -or $AdditionalApks.Count -ne 0) { throw 'Additional APK inventory is missing or not empty' }
    if ($null -eq $Inputs -or $Inputs.Count -ne 2) { throw 'Expected exactly two fixture inputs' }
    $hostItems=@($Inputs | Where-Object { $_.role -ceq 'host' })
    $testItems=@($Inputs | Where-Object { $_.role -ceq 'test' })
    if ($hostItems.Count -ne 1 -or $testItems.Count -ne 1) { throw 'Unknown or duplicate fixture input role' }
    if ($hostItems[0].path -cne $Record.host.apkPath -or $testItems[0].path -cne $Record.test.apkPath) { throw 'Fixture paths do not match provenance' }
    Assert-ApkSnapshotMatches $Record $hostItems[0].path $testItems[0].path
}

function Assert-ApkProvenanceFile {
    param([string]$Path,[string]$HostApk,[string]$TestApk)
    $expected=[IO.Path]::GetFullPath((Join-Path $script:SafetyProjectRoot 'app/build/outputs/android-test-safety/provenance.json'))
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Provenance file is missing' }
    $actual=(Resolve-Path -LiteralPath $Path).ProviderPath
    if (![string]::Equals($expected,$actual,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected provenance path' }
    if ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Symlinked provenance is not allowed' }
    $record=Get-Content -LiteralPath $actual -Raw | ConvertFrom-Json -ErrorAction Stop
    Assert-ApkProvenanceRecord $record $HostApk $TestApk
}

function Save-ApkProvenance {
    param([Parameter(Mandatory)]$Record)
    $directory=Join-Path $script:SafetyProjectRoot 'app/build/outputs/android-test-safety'
    if (!(Test-Path -LiteralPath $directory)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    if ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Provenance directory is a reparse point' }
    $target=Join-Path $directory 'provenance.json'
    $temp=Join-Path $directory ('.provenance-'+[guid]::NewGuid().ToString()+'.tmp')
    $backup=Join-Path $directory ('.provenance-'+[guid]::NewGuid().ToString()+'.bak')
    try {
        $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Record | ConvertTo-Json -Depth 12))
        $stream=[IO.FileStream]::new($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ([IO.File]::Exists($target)) {
            [IO.File]::Replace($temp,$target,$backup)
            [IO.File]::Delete($backup)
        } else { [IO.File]::Move($temp,$target) }
    } finally { if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) } }
}
