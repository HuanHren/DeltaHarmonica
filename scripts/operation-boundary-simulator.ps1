# Offline policy model only: no APK parser, UTP integration, or device transport.
# Its fixture identities and provenance are deliberately synthetic.
function Get-SimHash([byte[]]$bytes) {
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function New-SimulatedDevice { return @{ calls=0; officialChanges=0; isolated=$true } }
function Assert-SimulatedPlan($plan) {
    $hostId='com.huanhren.deltaharmonica.instrumented'; $testId="$hostId.test"
    if ($plan.task -cne 'fixture-task' -or $plan.plugin -cne 'fixture-plugin' -or $plan.target -cne $hostId -or $plan.runner -cne 'fixture-runner') { throw 'Untrusted execution context' }
    if (@($plan.artifacts).Count -ne 2 -or @($plan.uninstall).Count -ne 2) { throw 'Unexpected input or cleanup count' }
    foreach ($pair in @(@('host',$hostId),@('test',$testId))) {
        $a=@($plan.artifacts | Where-Object { $_.role -ceq $pair[0] })
        if ($a.Count -ne 1 -or $a[0].id -cne $pair[1] -or $a[0].manifest -cne $pair[1] -or $a[0].cert -cne 'fixture-cert' -or $a[0].provenance -cne 'fixture-build' -or $a[0].splitOf -cne '' -or (Get-SimHash $a[0].bytes) -cne $a[0].sha) { throw 'Invalid artifact' }
        if (@($plan.uninstall | Where-Object { $_ -ceq $pair[1] }).Count -ne 1) { throw 'Invalid cleanup' }
    }
}
function Invoke-SimulatedPlan($device,$plan,$beforeConsume) {
    Assert-SimulatedPlan $plan
    if ($null -ne $beforeConsume) { & $beforeConsume $plan }
    Assert-SimulatedPlan $plan
    # Fake counters only. No callback to external code after validation.
    $device.calls += @($plan.artifacts).Count + @($plan.uninstall).Count
}
function Invoke-SimulatedCrash($device) { $device.isolated=$true }
function Invoke-SimulatedCleanup($device) { $device.isolated=$true }
