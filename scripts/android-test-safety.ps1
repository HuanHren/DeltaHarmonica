$script:ProtectedAppId = 'com.huanhren.deltaharmonica'
$script:IsolatedAppId = 'com.huanhren.deltaharmonica.instrumented'
$script:IsolatedTestId = 'com.huanhren.deltaharmonica.instrumented.test'
$script:SafetyProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Assert-SingleDeviceSerial {
    param([string[]]$Serials, [AllowEmptyString()][string]$EnvironmentSerial)
    if ($null -eq $Serials -or $Serials.Count -ne 1 -or $Serials[0] -cnotmatch '^[A-Za-z0-9._:-]+$') { throw 'Exactly one explicit device serial is required' }
    if (![string]::IsNullOrEmpty($EnvironmentSerial)) {
        if ($EnvironmentSerial -cnotmatch '^[A-Za-z0-9._:-]+$' -or $EnvironmentSerial -cne $Serials[0]) { throw 'ANDROID_SERIAL conflicts with the explicit serial or contains multiple serials' }
    }
}

# The first whitelist revision has no approved UTP helper or SDK APKs.
function Assert-NoAdditionalApks {
    param([AllowEmptyCollection()][string[]]$Apks)
    if ($null -eq $Apks -or $Apks.Count -ne 0) { throw 'UTP additional APKs are not approved' }
}

function Assert-IsolatedIdentity {
    param([string]$AppId, [string]$TestId, [string]$TargetId, [string]$Runner, [string]$Variant, [string]$TestVariant)
    if ($AppId -cne $script:IsolatedAppId) { throw "Unsafe or unknown host applicationId: '$AppId'" }
    if ($TestId -cne $script:IsolatedTestId) { throw "Unsafe or unknown test applicationId: '$TestId'" }
    if ($TargetId -cne $script:IsolatedAppId) { throw "Unsafe or missing instrumentation targetPackage: '$TargetId'" }
    if ($Runner -cne 'com.huanhren.deltaharmonica.AccessibilityTestRunner') { throw "Unexpected instrumentation runner: '$Runner'" }
    if ($Variant -cne 'instrumented' -or $TestVariant -cne 'instrumentedAndroidTest') { throw 'Unexpected or missing build variant' }
}

function Get-ApkIdentity {
    param([Parameter(Mandatory)][string]$Aapt, [Parameter(Mandatory)][string]$Apk, [switch]$Instrumentation)
    if (!(Test-Path -LiteralPath $Aapt -PathType Leaf) -or !(Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'APK or aapt is missing' }
    $badging = & $Aapt dump badging $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Cannot parse APK: $Apk" }
    $matchesPackage = @($badging | Where-Object { $_ -cmatch "^package: name='([^']+)'" })
    if ($matchesPackage.Count -ne 1 -or $matchesPackage[0] -cnotmatch "^package: name='([^']+)'" ) { throw 'Missing or ambiguous APK package' }
    $result = @{ Package = $Matches[1]; Target = ''; Runner = '' }
    if ($Instrumentation) {
        $tree = & $Aapt dump xmltree $Apk AndroidManifest.xml 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Cannot parse test APK manifest' }
        $joined = $tree -join "`n"
        $blocks = [regex]::Matches($joined, '(?ms)^\s{4}E: instrumentation \(line=\d+\).*?(?=^\s{4}E: |\z)')
        if ($blocks.Count -ne 1) { throw 'Missing or ambiguous instrumentation declaration' }
        $targets = [regex]::Matches($blocks[0].Value, 'A: android:targetPackage\([^\r\n]*?\)="([^"]+)"')
        $runners = [regex]::Matches($blocks[0].Value, 'A: android:name\([^\r\n]*?\)="([^"]+)"')
        if ($targets.Count -ne 1 -or $runners.Count -ne 1) { throw 'Missing or ambiguous instrumentation target or runner' }
        $result.Target = $targets[0].Groups[1].Value; $result.Runner = $runners[0].Groups[1].Value
    }
    return $result
}

function Assert-ApkPair {
    param([string]$Aapt,[string]$AppApk,[string]$TestApk,[string]$AppMetadata,[string]$TestMetadata)
    if (!(Test-Path -LiteralPath $AppMetadata -PathType Leaf) -or !(Test-Path -LiteralPath $TestMetadata -PathType Leaf)) { throw 'Build metadata missing' }
    $expectedPaths=@(
        'app/build/outputs/apk/instrumented/app-instrumented.apk',
        'app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk',
        'app/build/outputs/apk/instrumented/output-metadata.json',
        'app/build/outputs/apk/androidTest/instrumented/output-metadata.json'
    )
    $actualPaths=@($AppApk,$TestApk,$AppMetadata,$TestMetadata)
    for ($i=0; $i -lt $expectedPaths.Count; $i++) {
        $expected=[IO.Path]::GetFullPath((Join-Path $script:SafetyProjectRoot $expectedPaths[$i]))
        $actual=(Resolve-Path -LiteralPath $actualPaths[$i] -ErrorAction Stop).ProviderPath
        if (![string]::Equals($expected,$actual,[StringComparison]::OrdinalIgnoreCase)) { throw "APK or metadata path does not match instrumented output: $actual" }
    }
    $appMeta = Get-Content -LiteralPath $AppMetadata -Raw | ConvertFrom-Json
    $testMeta = Get-Content -LiteralPath $TestMetadata -Raw | ConvertFrom-Json
    $app = Get-ApkIdentity -Aapt $Aapt -Apk $AppApk
    $test = Get-ApkIdentity -Aapt $Aapt -Apk $TestApk -Instrumentation
    Assert-IsolatedIdentity $app.Package $test.Package $test.Target $test.Runner $appMeta.variantName $testMeta.variantName
    if ($appMeta.applicationId -cne $app.Package -or $testMeta.applicationId -cne $test.Package) { throw 'Build metadata does not match APK identity' }
    if (@($appMeta.elements).Count -ne 1 -or @($testMeta.elements).Count -ne 1 -or $appMeta.elements[0].outputFile -cne [IO.Path]::GetFileName($AppApk) -or $testMeta.elements[0].outputFile -cne [IO.Path]::GetFileName($TestApk)) { throw 'Unexpected build output' }
    foreach ($pair in @(@($AppApk,$AppMetadata),@($TestApk,$TestMetadata))) {
        $apkItem=Get-Item -LiteralPath $pair[0] -Force
        $metadataItem=Get-Item -LiteralPath $pair[1] -Force
        if (($apkItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or ($metadataItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Symlinked APK or metadata is not allowed' }
        if ($apkItem.Directory.FullName -cne $metadataItem.Directory.FullName) { throw 'APK is not in its metadata output directory' }
    }
    return @{ AppHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $AppApk).Hash; TestHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $TestApk).Hash }
}

function Invoke-IsolatedDeviceWorkflow {
    param([string]$Aapt,[string]$AppApk,[string]$TestApk,[string]$AppMetadata,[string]$TestMetadata,
        [string]$Serial,[ValidateSet('Root','Accessibility')][string]$Backend='Root',
        [AllowEmptyString()][string]$EnvironmentSerial=$env:ANDROID_SERIAL,
        [Parameter(Mandatory)][scriptblock]$DeviceCommand)
    Assert-SingleDeviceSerial @($Serial) $EnvironmentSerial
    $identity=Assert-ApkPair $Aapt $AppApk $TestApk $AppMetadata $TestMetadata
    $devices=& $DeviceCommand @('devices')
    if ($null -eq $devices -or $devices.ExitCode -ne 0) { throw 'Cannot list devices' }
    $entries=@($devices.Output | Where-Object { $_ -match '^\S+\s+\S+' -and $_ -notmatch '^List of devices attached\s*$' })
    if ($entries.Count -ne 1 -or $entries[0] -cnotmatch ('^'+[regex]::Escape($Serial)+'\s+device\s*$')) { throw 'Exactly the specified single online device is required; offline and unauthorized devices are not ignored' }
    $protected=& $DeviceCommand @('-s',$Serial,'shell','pm','path',$script:ProtectedAppId)
    if ($null -eq $protected) { throw 'Cannot determine protected package status' }
    if ($protected.ExitCode -eq 0) {
        if (@($protected.Output | Where-Object { $_ -match '^package:/' }).Count -eq 0) { throw 'Cannot verify protected package status' }
    } elseif (($protected.Output -join '') -notmatch 'Unknown package|not found') { throw 'Cannot determine protected package status' }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $AppApk).Hash -cne $identity.AppHash -or (Get-FileHash -Algorithm SHA256 -LiteralPath $TestApk).Hash -cne $identity.TestHash) { throw 'APK changed after validation' }
    $installHost=& $DeviceCommand @('-s',$Serial,'install','-r',$AppApk)
    if ($null -eq $installHost -or $installHost.ExitCode -ne 0) { throw 'Isolated host installation failed; no uninstall attempted' }
    $installTest=& $DeviceCommand @('-s',$Serial,'install','-r','-t',$TestApk)
    if ($null -eq $installTest -or $installTest.ExitCode -ne 0) { throw 'Isolated test installation failed; no uninstall attempted' }
    if ($Backend -eq 'Accessibility') {
        $enabled=& $DeviceCommand @('-s',$Serial,'shell','settings','get','secure','enabled_accessibility_services')
        if ($null -eq $enabled -or $enabled.ExitCode -ne 0 -or ($enabled.Output -join '') -notmatch [regex]::Escape($script:IsolatedAppId+'/')) { throw 'Enable the isolated host accessibility service before testing' }
    }
    $class='com.huanhren.deltaharmonica.StageOneDeviceTest'
    $methods=@('dragSaveRecreateAndReload','rtlCanvasKeepsPhysicalScreenCoordinates')
    if ($Backend -eq 'Root') { $methods+=@('rootGestureActuallyHitsSavedTarget','leavingRootTestPageCancelsPendingTap') }
    else { $methods+=@('enabledAccessibilityGestureActuallyHitsSavedTarget','leavingTestPageCancelsPendingTap') }
    $selection=($methods | ForEach-Object { "$class#$_" }) -join ','
    $output=& $DeviceCommand @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$selection,"$script:IsolatedTestId/com.huanhren.deltaharmonica.AccessibilityTestRunner")
    if ($null -eq $output -or $output.ExitCode -ne 0 -or ($output.Output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(3|4)' -or ($output.Output -join "`n") -notmatch 'OK \(') { throw 'Isolated instrumentation failed or skipped' }
    return $output.Output
}
