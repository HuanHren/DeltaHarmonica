$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'apk-provenance.ps1')
$app='app/build/outputs/apk/instrumented/app-instrumented.apk'
$test='app/build/outputs/apk/androidTest/instrumented/app-instrumented-androidTest.apk'
$count=0
function ExpectFailure([scriptblock]$action) { $rejected=$false; try { & $action | Out-Null } catch { $rejected=$true }; if(!$rejected){throw 'Expected rejection'}; $script:count++ }
$candidate=New-ApkProvenance -HostApk $app -TestApk $test
Assert-ApkSnapshotMatches $candidate $app $test; $count++
ExpectFailure { Assert-ApkProvenanceRecord $candidate $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.host.apkSha256='0'*64
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.test.certificateSha256='0'*64
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.test.manifestSha256='0'*64
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.test.targetPackage='com.huanhren.deltaharmonica'
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.test.runner='unexpected.Runner'
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.host.apkPath='C:\wrong\app-instrumented.apk'
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.test.apkSha256='0'*64
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
$changed=$candidate | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$changed.schemaVersion=99
ExpectFailure { Assert-ApkSnapshotMatches $changed $app $test }
ExpectFailure { Assert-ApkSnapshotMatches $null $app $test }
ExpectFailure { Assert-ApkProvenanceFile 'missing-provenance.json' $app $test }
ExpectFailure { New-ApkProvenance -HostApk 'app/build/outputs/apk/debug/app-debug.apk' -TestApk $test }
ExpectFailure { New-ApkProvenance -HostApk $app -TestApk 'app/build/outputs/apk/debug/app-debug.apk' }
$inputs=@([pscustomobject]@{role='host';path=$candidate.host.apkPath},[pscustomobject]@{role='test';path=$candidate.test.apkPath})
Assert-SyntheticUtpInventory $candidate $inputs @() 'offline-explicit-fixture'; $count++
ExpectFailure { Assert-SyntheticUtpInventory $candidate $inputs @() 'unknown' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate @($inputs[0]) @() 'offline-explicit-fixture' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate @($inputs[0],$inputs[0]) @() 'offline-explicit-fixture' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate $inputs @('app/build/outputs/apk/debug/app-debug.apk') 'offline-explicit-fixture' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate $inputs @('unknown.apk') 'offline-explicit-fixture' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate $inputs $null 'offline-explicit-fixture' }
ExpectFailure { Assert-SyntheticUtpInventory $candidate $inputs @('third-party.apk') 'offline-explicit-fixture' }
"Offline provenance checks passed: $count (no device commands)"
