$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operation-boundary-simulator.ps1')
$hostId = 'com.huanhren.deltaharmonica.instrumented'
$testId = "$hostId.test"
$official = 'com.huanhren.deltaharmonica'
function Make-Artifact($id, $role, $content) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($content)
    return @{ id=$id; role=$role; bytes=$bytes; sha=(Get-SimHash $bytes); cert='fixture-cert'; manifest=$id; provenance='fixture-build'; splitOf='' }
}
function Make-Plan {
    $a=Make-Artifact $hostId 'host' 'host'
    $b=Make-Artifact $testId 'test' 'test'
    return @{ task='fixture-task'; plugin='fixture-plugin'; artifacts=@($a,$b); uninstall=@($hostId,$testId); target=$hostId; runner='fixture-runner' }
}
function Assert-Rejected($name, $plan, $mutate) {
    $device=New-SimulatedDevice
    try { Invoke-SimulatedPlan $device $plan $mutate; throw "Unexpected acceptance: $name" }
    catch { if ($_.Exception.Message -like 'Unexpected acceptance*') { throw } }
    if ($device.calls -ne 0 -or $device.officialChanges -ne 0) { throw "Unsafe calls in $name" }
    Write-Output "PASS reject: $name"
}
$p=Make-Plan; $p.artifacts[0].provenance='unknown'; Assert-Rejected 'unauthorized provenance' $p $null
$p=Make-Plan; $p.artifacts[0].id=$official; Assert-Rejected 'official installation' $p $null
$p=Make-Plan; $p.artifacts+=,(Make-Artifact 'unknown.example' 'unknown' 'other'); Assert-Rejected 'unknown APK' $p $null
$p=Make-Plan; $p.artifacts+=,(Make-Artifact $hostId 'split' 'split'); Assert-Rejected 'unknown split' $p $null
$p=Make-Plan; $p.uninstall+=@('unknown.example'); Assert-Rejected 'unauthorized uninstall' $p $null
$p=Make-Plan; $p.uninstall+=@($official); Assert-Rejected 'official uninstall' $p $null
$p=Make-Plan; Assert-Rejected 'file replaced after inspection' $p { param($plan) $plan.artifacts[0].bytes=[Text.Encoding]::UTF8.GetBytes('replacement') }
$p=Make-Plan; $p.uninstall=@($hostId); Assert-Rejected 'cleanup differs from approved plan' $p $null
$p=Make-Plan; $p.plugin='unknown'; Assert-Rejected 'unknown plugin' $p $null
$p=Make-Plan; Assert-Rejected 'plugin adds APK after validation' $p { param($plan) $plan.artifacts+=,(Make-Artifact 'unknown.example' 'unknown' 'late') }
$p=Make-Plan; $p.artifacts[0].cert='other'; Assert-Rejected 'wrong signing identity' $p $null
$p=Make-Plan; $p.target=$official; Assert-Rejected 'wrong target' $p $null
$p=Make-Plan; $d=New-SimulatedDevice; Invoke-SimulatedPlan $d $p $null; if ($d.calls -ne 4 -or $d.officialChanges -ne 0) { throw 'Expected authorized fake operations' }; 'PASS authorized fixture'
$p.artifacts[0].bytes=[Text.Encoding]::UTF8.GetBytes('changed-after-prior-run'); Assert-Rejected 'replacement after earlier install' $p $null
$d=New-SimulatedDevice; Invoke-SimulatedCrash $d; Invoke-SimulatedCleanup $d; Invoke-SimulatedCleanup $d; if ($d.calls -ne 0 -or $d.officialChanges -ne 0 -or !$d.isolated) { throw 'Crash/cleanup must remain isolated and idempotent' }; 'PASS abnormal exit and repeated cleanup'
'Offline simulator: 13 rejection cases and 2 positive/lifecycle cases passed; no device access.'
