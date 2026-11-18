$ErrorActionPreference='Stop'
$source=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../app/src/androidTest/java/com/huanhren/deltaharmonica/ImportLifecycleDeviceTest.kt')
if($source -notmatch '@After\s+fun releaseProviderAndPreserveTestEvidence' -or $source -notmatch 'resolver\.call\(providerUri,"release"') {throw 'Provider release is missing'}
if($source -match 'songs\.delete\s*\('){throw 'Automatic song deletion remains'}
'PASS import lifecycle retains imported evidence and releases provider (static check only)'
