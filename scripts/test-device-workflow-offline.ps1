$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'android-test-safety.ps1')
$entryRefused=$false
try { & (Join-Path $PSScriptRoot 'test-device.ps1') -Serial 'FAKE123' } catch { $entryRefused=($_.Exception.Message -match 'device entry is disabled') }
if (!$entryRefused) { throw 'Device entry must fail closed before any external command' }
$aapt=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe'
$app='app/build/outputs/apk/instrumented/app-instrumented.apk'
$test='app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk'
$am='app/build/outputs/apk/instrumented/output-metadata.json'
$tm='app/build/outputs/apk/androidTest/instrumented/output-metadata.json'
$calls=[System.Collections.Generic.List[string]]::new()
$fake = { param([string[]]$argv)
    $calls.Add(($argv -join ' '))
    if ($argv[0] -eq 'devices') { return @{ExitCode=0; Output=@('List of devices attached','FAKE123 device')} }
    if (($argv -join ' ') -match 'pm path') { return @{ExitCode=0; Output=@('package:/data/app/protected/base.apk')} }
    if (($argv -join ' ') -match 'am instrument') { return @{ExitCode=0; Output=@('OK (1 test)')} }
    return @{ExitCode=0; Output=@('Success')}
}.GetNewClosure()
foreach($badApp in @('app/build/outputs/apk/debug/app-debug.apk','missing.apk')) {
    $failed=$false
    try { Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $badApp -TestApk $test -AppMetadata $am -TestMetadata $tm -Serial 'FAKE123' -DeviceCommand $fake | Out-Null } catch { $failed=$true }
    if (!$failed -or $calls.Count -ne 0) { throw 'Invalid identity reached device boundary' }
}
$failed=$false
try { Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $app -TestApk $test -AppMetadata 'app/build/outputs/apk/debug/output-metadata.json' -TestMetadata $tm -Serial 'FAKE123' -DeviceCommand $fake | Out-Null } catch { $failed=$true }
if (!$failed -or $calls.Count -ne 0) { throw 'Metadata mismatch reached device boundary' }
$failed=$false
try { Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $app -TestApk $test -AppMetadata $am -TestMetadata $tm -Serial 'FAKE123' -EnvironmentSerial 'OTHER' -DeviceCommand $fake | Out-Null } catch { $failed=$true }
if (!$failed -or $calls.Count -ne 0) { throw 'Conflicting environment serial reached device boundary' }
$realParser = ${function:Get-ApkIdentity}
try {
    function Get-ApkIdentity {
        param([string]$Aapt,[string]$Apk,[switch]$Instrumentation)
        if ($Instrumentation) { return @{Package='com.huanhren.deltaharmonica.instrumented.test'; Target='com.huanhren.deltaharmonica'; Runner='com.huanhren.deltaharmonica.AccessibilityTestRunner'} }
        return @{Package='com.huanhren.deltaharmonica.instrumented'; Target=''; Runner=''}
    }
    $failed=$false
    try { Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $app -TestApk $test -AppMetadata $am -TestMetadata $tm -Serial 'FAKE123' -DeviceCommand $fake | Out-Null } catch { $failed=$true }
    if (!$failed -or $calls.Count -ne 0) { throw 'Protected instrumentation target reached device boundary' }
} finally { ${function:Get-ApkIdentity} = $realParser }
$ambiguousCalls=[System.Collections.Generic.List[string]]::new()
$ambiguous = { param([string[]]$argv)
    $ambiguousCalls.Add(($argv -join ' '))
    return @{ExitCode=0; Output=@('List of devices attached','FAKE123 device','OTHER offline')}
}.GetNewClosure()
$failed=$false
try { Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $app -TestApk $test -AppMetadata $am -TestMetadata $tm -Serial 'FAKE123' -DeviceCommand $ambiguous | Out-Null } catch { $failed=$true }
if (!$failed -or $ambiguousCalls.Count -ne 1 -or $ambiguousCalls[0] -ne 'devices') { throw 'Ambiguous device list was not rejected before modification' }
Invoke-IsolatedDeviceWorkflow -Aapt $aapt -AppApk $app -TestApk $test -AppMetadata $am -TestMetadata $tm -Serial 'FAKE123' -DeviceCommand $fake | Out-Null
if ($calls.Count -ne 5 -or $calls[0] -ne 'devices' -or $calls[1] -notmatch 'pm path com\.huanhren\.deltaharmonica$' -or $calls[2] -notmatch '^\-s FAKE123 install -r ' -or $calls[3] -notmatch '^\-s FAKE123 install -r -t ' -or $calls[4] -notmatch 'am instrument') { throw "Unexpected fake command sequence: $($calls -join '; ')" }
'Offline mocked device workflow checks passed: 8 (no ADB)'
