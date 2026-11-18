[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$HostSha256,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$TestSha256,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'apk-provenance.ps1')
. (Join-Path $PSScriptRoot 'safe-device-input.ps1')

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$adbDigest = 'B4A6B455702684652CCCF7B46258B29E653538904359A58FD4931CF3EF286B3F'
$adbPort = 5037
$runner = 'com.huanhren.deltaharmonica.AccessibilityTestRunner'
$hostPath = Join-Path $script:SafetyProjectRoot 'app/build/outputs/apk/instrumented/app-instrumented.apk'
$testPath = Join-Path $script:SafetyProjectRoot 'app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk'
$leaseList = [Collections.Generic.List[IDisposable]]::new()
$runMutex = $null

function Get-LocalAdbServer {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $adbPort)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1') {
        throw 'Expected one existing IPv4 loopback ADB listener'
    }
    $process = Get-Process -Id $listeners[0].OwningProcess
    if ($process.Path -ine $adb -or (Get-FileHash -LiteralPath $process.Path).Hash -cne $adbDigest) {
        throw 'ADB Server process does not match the pinned SDK client'
    }
    return "$($process.Id):$($process.StartTime.ToUniversalTime().Ticks)"
}

function Assert-AdbServerVersion {
    # Query the existing listener directly. The adb CLI can restart an incompatible server.
    $connection = [Net.Sockets.TcpClient]::new('127.0.0.1', $adbPort)
    try {
        $connection.ReceiveTimeout = 2500
        $connection.SendTimeout = 2500
        $stream = $connection.GetStream()
        $writer = [Text.Encoding]::ASCII.GetBytes('000chost:version')
        $stream.Write($writer, 0, $writer.Length)
        $reader = [IO.BinaryReader]::new($stream)
        if ([Text.Encoding]::ASCII.GetString($reader.ReadBytes(12)) -cne 'OKAY00040029') {
            throw 'ADB Server protocol version differs from pinned client'
        }
    } finally { $connection.Dispose() }
}

function Write-DeviceEvidence([string]$Step, [string]$Detail) {
    $entry = [ordered]@{ utc = [DateTimeOffset]::UtcNow.ToString('o'); step = $Step; detail = $Detail }
    [IO.File]::AppendAllText($evidence, ((ConvertTo-Json -InputObject $entry -Compress) + "`n"))
}

function Invoke-PinnedAdb([string[]]$Arguments, [string]$Step, [switch]$PrivateOutput) {
    if ((Get-LocalAdbServer) -cne $server) { throw 'ADB Server identity changed before command' }
    Assert-AdbServerVersion
    $output = @(& $adb -H 127.0.0.1 -P $adbPort @Arguments 2>&1 | ForEach-Object {
        $line = [string]$_
        if ($Step -ceq 'instrumentation') { Write-DeviceEvidence 'instrumentation-line' $line }
        $line
    })
    $code = $LASTEXITCODE
    $detail = if ($PrivateOutput) { 'redacted device inventory' } else { $output -join "`n" }
    Write-DeviceEvidence $Step "exit=$code `n$detail"
    if ((Get-LocalAdbServer) -cne $server -or $code -ne 0) { throw "ADB command failed or server changed: $Step" }
    return $output
}

function Confirm-InstalledApk([string]$Package, [string]$Digest) {
    if ($Package -cne $script:IsolatedAppId -and $Package -cne $script:IsolatedTestId) {
        throw 'Package is outside the APK verification allowlist'
    }
    $path = @(Invoke-PinnedAdb ($route + @('shell','pm','path','--user','0',$Package)) "path:$Package")
    if ($path.Count -ne 1 -or $path[0] -cnotmatch ('^package:(/data/app/[~A-Za-z0-9_=.-]+/' +
        [regex]::Escape($Package) + '-[A-Za-z0-9_=.-]+/base\.apk)$')) { throw 'Unexpected installed APK path or split' }
    $remoteFile = $Matches[1]
    $hash = @(Invoke-PinnedAdb ($route + @('shell','sha256sum',$remoteFile)) "digest:$Package")
    if ($hash.Count -ne 1 -or $hash[0] -notmatch ('^' + [regex]::Escape($Digest) + '\s+' +
        [regex]::Escape($remoteFile) + '$')) { throw 'Installed APK bytes do not match authorized bytes' }
}

try {
    $pair = New-ApkProvenance -HostApk $hostPath -TestApk $testPath
    if ($pair.host.apkSha256 -ine $HostSha256 -or $pair.test.apkSha256 -ine $TestSha256) {
        throw 'Build-output hashes differ from the authorized pair'
    }
    $directory = New-PrivateApkDirectory (Join-Path $env:LOCALAPPDATA ('DH-SAFE-' + [guid]::NewGuid().ToString('N')))
    $evidence = Join-Path $directory 'device-commands.jsonl'
    Write-Output "Evidence: $evidence"
    $apks = @(
        @{source=$hostPath; expected=$pair.host; digest=$HostSha256; isTest=$false}
        @{source=$testPath; expected=$pair.test; digest=$TestSha256; isTest=$true}
    )
    foreach ($apk in $apks) {
        $apk.path = Join-Path $directory ($apk.digest.ToLowerInvariant() + '.apk')
        [IO.File]::Copy($apk.source, $apk.path, $false)
        $apk.lease = Open-InstallFile $apk.path
        $leaseList.Add($apk.lease)
        Assert-PrivateApkAcl $apk.path
        Assert-InstallFile $apk.lease $apk.digest
        $identity = Get-ApkAudit -Apk $apk.path -Instrumentation:$apk.isTest
        foreach ($field in @('apkSha256','manifestSha256','certificateSha256','applicationId','targetPackage','runner')) {
            if ($identity.$field -cne $apk.expected.$field) { throw "Staged APK identity mismatch: $field" }
        }
        $aapt = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe'
        $tree = @(& $aapt dump xmltree $apk.path AndroidManifest.xml)
        if ($LASTEXITCODE -ne 0 -or ($tree -match '^\s+A: (split=|android:sharedUserId)')) {
            throw 'Split or shared UID APKs are forbidden'
        }
        if (!$apk.isTest -and (($tree -join "`n") -notmatch '\.IsolatedTestApplication"' -or
            ($tree -match 'TouchAccessibilityService|CalibrationOverlayService|SYSTEM_ALERT_WINDOW|^\s+E: instrumentation'))) {
            throw 'Host manifest does not enforce the isolated application boundary'
        }
        Write-DeviceEvidence 'verified-local-apk' "$($identity.applicationId) $($identity.apkSha256) $($apk.lease.Identity) $($identity.certificateSha256) $($identity.manifestSha256)"
    }
    if (!$Execute) { return }
    $runMutex = Enter-DeviceRun

    foreach ($name in @('ADB_SERVER_SOCKET','ANDROID_ADB_SERVER_ADDRESS','ANDROID_ADB_SERVER_PORT','ADB_SERVER_PORT','ADB_VENDOR_KEYS')) {
        if ([Environment]::GetEnvironmentVariable($name)) { throw "ADB redirection override is set: $name" }
    }
    $client = Open-InstallFile $adb
    $leaseList.Add($client)
    Assert-InstallFile $client $adbDigest
    $version = @(& $adb version)
    if ($LASTEXITCODE -ne 0 -or $version[0] -cne 'Android Debug Bridge version 1.0.41' -or
        $version[1] -cnotmatch '^Version 37\.0\.1-\d+$') { throw 'Unexpected client version' }
    $server = Get-LocalAdbServer
    Write-DeviceEvidence 'adb-identity' "$adb $adbDigest listener=127.0.0.1:$adbPort process=$server client=$($version[1])"
    $inventory = @(Invoke-PinnedAdb @('devices','-l') 'devices' -PrivateOutput | Where-Object { $_ -and $_ -cne 'List of devices attached' })
    if ($inventory.Count -ne 1 -or $inventory[0] -cnotmatch '^([A-Za-z0-9._-]+)\s+device\s+.*\btransport_id:(\d+)\s*$') {
        throw 'Exactly one online USB device is required'
    }
    $serial = $Matches[1]
    $route = @('-t',$Matches[2])
    Assert-SingleDeviceSerial @($serial) $env:ANDROID_SERIAL
    foreach ($probe in @(
        @{args=@('get-serialno'); value=$serial; step='serial'},
        @{args=@('shell','getprop','ro.product.manufacturer'); value='Xiaomi'; step='manufacturer'},
        @{args=@('shell','getprop','ro.product.model'); value='23127PN0CC'; step='model'},
        @{args=@('shell','getprop','ro.build.version.sdk'); value='36'; step='api'},
        @{args=@('shell','getprop','ro.kernel.qemu'); value=''; step='emulator'},
        @{args=@('shell','am','get-current-user'); value='0'; step='foreground-user'}
    )) {
        if (((Invoke-PinnedAdb ($route + $probe.args) $probe.step -PrivateOutput:($probe.step -eq 'serial')) -join "`n").Trim() -cne $probe.value) {
            throw "Unexpected Xiaomi identity or Android user: $($probe.step)"
        }
    }
    $packageList = @('shell','pm','list','packages','--user','0',$script:ProtectedAppId)
    $formalEntry = 'package:' + $script:ProtectedAppId
    $formalBefore = @(Invoke-PinnedAdb ($route + $packageList) 'formal-package-before') -ccontains $formalEntry
    try {
        foreach ($apk in $apks) {
            Assert-InstallFile $apk.lease $apk.digest
            $options = @('install','-r','--streaming','--no-incremental','--no-fastdeploy','--user','0')
            if ($apk.isTest) { $options += '-t' }
            $result = @(Invoke-PinnedAdb ($route + $options + @($apk.path)) "install:$($apk.expected.applicationId)")
            Assert-InstallFile $apk.lease $apk.digest
            if ($result -cnotcontains 'Success') { throw 'Package manager did not explicitly confirm installation' }
            Confirm-InstalledApk $apk.expected.applicationId $apk.digest
        }
        $classes = @('IsolatedStorageDeviceTest','DocumentTransferDeviceTest','ImportLifecycleDeviceTest','MidiImportDeviceTest','WorkspaceProtectionDeviceTest','StageOneDeviceTest#rtlCanvasKeepsPhysicalScreenCoordinates','StageOneDeviceTest#dragSaveRecreateAndReload','ProductUiDeviceTest')
        $selection = ($classes | ForEach-Object { "com.huanhren.deltaharmonica.$_" }) -join ','
        $run = @(Invoke-PinnedAdb ($route + @('shell','am','instrument','--user','0','-w','-r','-e','class',$selection,"$($script:IsolatedTestId)/$runner")) 'instrumentation')
        if (($run -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' -or
            ($run -join "`n") -notmatch '(?m)^OK \(12 tests?\)\s*$') { throw 'Instrumentation did not report all twelve selected tests passing' }
        Write-Output 'PASS: twelve isolated Xiaomi instrumentation tests'
    } finally {
        $formalAfter = @(Invoke-PinnedAdb ($route + $packageList) 'formal-package-after') -ccontains $formalEntry
        if ($formalBefore -ne $formalAfter) { throw 'Protected package inventory changed' }
    }
} finally {
    foreach ($lease in $leaseList) { $lease.Dispose() }
    if ($runMutex) {
        $runMutex.ReleaseMutex()
        $runMutex.Dispose()
    }
}
