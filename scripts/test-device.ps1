[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('Root', 'Accessibility')][string]$Backend = 'Root')
$ErrorActionPreference = 'Stop'
throw 'SAFE-1B.2: device entry is disabled until the Gradle task whitelist and all APK checks are fully verified.'
. (Join-Path $PSScriptRoot 'android-test-safety.ps1')
if ($Serial -cnotmatch '^[A-Za-z0-9._:-]+$') { throw 'A single explicit device serial is required' }
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Isolated APK build failed' }
    $sdkLine = @(Get-Content -LiteralPath local.properties | Where-Object { $_ -match '^sdk.dir=' })
    if ($sdkLine.Count -ne 1) { throw 'Exactly one SDK path is required' }
    $sdkPath = ($sdkLine[0] -replace '^sdk.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
    $aaptPath = Join-Path $sdkPath 'build-tools/35.0.0/aapt.exe'
    $adbPath = Join-Path $sdkPath 'platform-tools/adb.exe'
    $appApk = (Resolve-Path -LiteralPath 'app/build/outputs/apk/instrumented/app-instrumented.apk').Path
    $testApk = (Resolve-Path -LiteralPath 'app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk').Path
    if (!(Test-Path -LiteralPath $adbPath -PathType Leaf)) { throw 'adb is missing' }
    $deviceCommand = { param([string[]]$argv)
        $output=@(& $adbPath @argv 2>&1)
        return @{ ExitCode=$LASTEXITCODE; Output=$output }
    }.GetNewClosure()
    Invoke-IsolatedDeviceWorkflow -Aapt $aaptPath -AppApk $appApk -TestApk $testApk `
        -AppMetadata 'app/build/outputs/apk/instrumented/output-metadata.json' `
        -TestMetadata 'app/build/outputs/apk/androidTest/instrumented/output-metadata.json' `
        -Serial $Serial -Backend $Backend -DeviceCommand $deviceCommand
} finally { Pop-Location }
