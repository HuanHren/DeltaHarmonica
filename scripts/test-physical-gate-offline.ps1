$ErrorActionPreference='Stop'
$entry=Join-Path $PSScriptRoot 'test-device-physical.ps1'
foreach($action in @('Install','Run','PostCheck','Verify')) {
    $blocked=$false
    try { & $entry -Action $action -Serial 'SYNTHETIC' | Out-Null } catch { $blocked=($_.Exception.Message -match 'BLOCKED') }
    if(!$blocked) { throw "Unsafe or unexpected gate for $action" }
    "PASS $action fails closed"
}
$blocked=$false
try { & $entry -Action Audit | Out-Null } catch { $blocked=$true }
if(!$blocked) { throw 'Missing serial accepted' }
'PASS serial required'
'Physical gate: 5 offline checks; no ADB invoked.'
