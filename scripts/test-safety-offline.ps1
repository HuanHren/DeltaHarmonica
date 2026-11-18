$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'android-test-safety.ps1')
$runner='com.huanhren.deltaharmonica.AccessibilityTestRunner'
$checks=0
function ExpectFailure([scriptblock]$case) {
    $failed=$false
    try { & $case | Out-Null } catch { $failed=$true }
    if (!$failed) { throw 'Expected rejection did not occur' }
    $script:checks++
}
function ExpectSuccess([scriptblock]$case) { & $case | Out-Null; $script:checks++ }
$hostId='com.huanhren.deltaharmonica.instrumented'
$testId='com.huanhren.deltaharmonica.instrumented.test'
$official='com.huanhren.deltaharmonica'
ExpectFailure { Assert-IsolatedIdentity $official $testId $hostId $runner 'instrumented' 'instrumentedAndroidTest' }
ExpectFailure { Assert-IsolatedIdentity $hostId $testId $official $runner 'instrumented' 'instrumentedAndroidTest' }
ExpectFailure { Assert-IsolatedIdentity $hostId $testId '' $runner 'instrumented' 'instrumentedAndroidTest' }
ExpectFailure { Assert-IsolatedIdentity $hostId $official $hostId $runner 'instrumented' 'instrumentedAndroidTest' }
ExpectFailure { Assert-IsolatedIdentity $hostId $testId $hostId $runner 'debug' 'instrumentedAndroidTest' }
ExpectSuccess { Assert-IsolatedIdentity $hostId $testId $hostId $runner 'instrumented' 'instrumentedAndroidTest' }
ExpectSuccess { Assert-SingleDeviceSerial @('FAKE123') 'FAKE123' }
ExpectFailure { Assert-SingleDeviceSerial @() '' }
ExpectFailure { Assert-SingleDeviceSerial @('FAKE123','OTHER') '' }
ExpectFailure { Assert-SingleDeviceSerial @('FAKE123,OTHER') '' }
ExpectFailure { Assert-SingleDeviceSerial @('FAKE123') 'OTHER' }
ExpectSuccess { Assert-NoAdditionalApks @() }
ExpectFailure { Assert-NoAdditionalApks @('com.huanhren.deltaharmonica') }
ExpectFailure { Assert-NoAdditionalApks @('unknown.apk') }
$aapt=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe'
$app='app/build/outputs/apk/instrumented/app-instrumented.apk'
$test='app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk'
$am='app/build/outputs/apk/instrumented/output-metadata.json'
$tm='app/build/outputs/apk/androidTest/instrumented/output-metadata.json'
ExpectSuccess { Assert-ApkPair $aapt $app $test $am $tm }
ExpectFailure { Assert-ApkPair $aapt 'app/build/outputs/apk/debug/app-debug.apk' $test $am $tm }
ExpectFailure { Assert-ApkPair $aapt 'missing.apk' $test $am $tm }
ExpectFailure { Assert-ApkPair $aapt $app $test 'app/build/outputs/apk/debug/output-metadata.json' $tm }
$wrongDir=Join-Path $env:TEMP ('misplaced-apk-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $wrongDir | Out-Null
try {
    $misplaced=Join-Path $wrongDir 'app-instrumented.apk'
    Copy-Item -LiteralPath $app -Destination $misplaced
    ExpectFailure { Assert-ApkPair $aapt $misplaced $test $am $tm }
    $wrongMetadata=Join-Path $wrongDir 'output-metadata.json'
    Copy-Item -LiteralPath $am -Destination $wrongMetadata
    ExpectFailure { Assert-ApkPair $aapt $misplaced $test $wrongMetadata $tm }
} finally {
    if (Test-Path -LiteralPath $misplaced -PathType Leaf) { Remove-Item -LiteralPath $misplaced -Force }
    if (Test-Path -LiteralPath $wrongMetadata -PathType Leaf) { Remove-Item -LiteralPath $wrongMetadata -Force }
    Remove-Item -LiteralPath $wrongDir -Force
}
$invalid=Join-Path $env:TEMP ('invalid-apk-'+[guid]::NewGuid().ToString()+'.apk')
try {
    [IO.File]::WriteAllText($invalid,'not an apk')
    ExpectFailure { Assert-ApkPair $aapt $invalid $test $am $tm }
} finally { if (Test-Path -LiteralPath $invalid -PathType Leaf) { Remove-Item -LiteralPath $invalid -Force } }
"Offline safety checks passed: $checks"
