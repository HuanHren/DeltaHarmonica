# Offline thought model only. No implementation may use this to authorize a device.
function Test-ModelSession($plan,[string]$expected) { return $plan.session -ceq $expected }
function Assert-ModelCleanup($plan) {
    $allowed=@('com.huanhren.deltaharmonica.instrumented','com.huanhren.deltaharmonica.instrumented.test')
    $seen=[System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $plan.requested) {
        if ($id -cnotin $allowed -or $id -cnotin $plan.installed -or $id -cnotin $plan.authorized -or $id -cin $plan.completed -or !$seen.Add($id)) {
            $plan.log.Add("cleanup denied: $id")
            throw 'Unauthorized cleanup'
        }
    }
}
