# Pure fixtures: no device connector, no VM operations, no APK authorization.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'session-policy-model.ps1')
$hostId='com.huanhren.deltaharmonica.instrumented'
$testId="$hostId.test"
$official='com.huanhren.deltaharmonica'
function Plan { return @{ session='run-1'; installed=@($hostId,$testId); authorized=@($hostId,$testId); requested=@($testId); completed=@(); log=[System.Collections.Generic.List[string]]::new(); deviceCalls=0; officialInstalls=0; officialUninstalls=0; officialChanges=0 } }
function Denied($name,$p) {
    $ok=$false; try { Assert-ModelCleanup $p } catch { $ok=$true }
    if (!$ok -or $p.deviceCalls -ne 0 -or $p.officialInstalls -ne 0 -or $p.officialUninstalls -ne 0 -or $p.officialChanges -ne 0 -or $p.log.Count -eq 0) { throw "Failed rejection: $name" }
    # Rejection must not create any permit for another session.
    $p.session='other-run'; if (Test-ModelSession $p 'run-1') { throw 'Stale session accepted' }
    "PASS $name"
}
$p=Plan; $p.requested=@($official); Denied 'official cleanup' $p
$p=Plan; $p.requested=@('unknown'); Denied 'unknown cleanup' $p
$p=Plan; $p.installed=@($hostId); Denied 'not installed this session' $p
$p=Plan; $p.authorized=@($hostId); Denied 'no cleanup authority' $p
$p=Plan; $p.requested=@($testId,$testId); Denied 'duplicate cleanup request' $p
$p=Plan; $p.completed=@($testId); Denied 'already cleaned' $p
$p=Plan; $p.installed=@($hostId); $p.requested=@($testId,$hostId); Denied 'cleanup superset' $p
$p=Plan; Assert-ModelCleanup $p; if ($p.deviceCalls -ne 0 -or $p.requested.Count -ne 1) { throw 'Subset should validate without operations' }; 'PASS authorized cleanup subset'
'Session policy: 7 rejection cases and 1 subset case passed; model only.'
